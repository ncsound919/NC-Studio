package com.example.session.net

import com.example.session.protocol.DecodeResult
import com.example.session.protocol.Envelope
import com.example.session.protocol.MessageCodec
import com.example.session.protocol.Ping
import com.example.session.protocol.Pong
import com.example.session.protocol.SessionMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Everything the session layer reacts to from the wire. */
sealed interface TransportEvent {
    /** A fresh socket is up. Commands must wait for a Hello on this generation. */
    data class Connected(val generation: Long) : TransportEvent

    data class Disconnected(val reason: String) : TransportEvent

    data class Received(val envelope: Envelope, val message: SessionMessage) : TransportEvent

    class BinaryReceived(val payload: ByteArray) : TransportEvent

    data class Rtt(val rttMs: Long, val jitterMs: Long, val missedPings: Int) : TransportEvent

    data class Degraded(val rttMs: Long, val missedPings: Int) : TransportEvent
}

/**
 * Reconnecting WebSocket transport.
 *
 * Control and telemetry share the socket (the design's stated fallback when
 * WebRTC data channels are unavailable); monitor audio uses binary frames.
 * Nothing is queued across a reconnect: a command that did not make it onto
 * the old socket is dropped, and the engineer must reissue it after the fresh
 * Hello/StateSnapshot handshake. That is the rule that stops a stale
 * `StartTake` from starting a take minutes later.
 */
class WebSocketTransport(
    private val settings: ConnectionSettings,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val codec: MessageCodec = MessageCodec(),
    private val heartbeatMs: Long = 1_000L,
    private val degradedAfterMissedPings: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val eventsFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<TransportEvent> get() = eventsFlow

    private val connectedFlow = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = connectedFlow

    private val rttFlow = MutableStateFlow(-1L)
    val rttMs: StateFlow<Long> get() = rttFlow

    private val jitterFlow = MutableStateFlow(0L)
    val jitterMs: StateFlow<Long> get() = jitterFlow

    private val active = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val generation = AtomicLong(0)

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var missedPings = 0

    @Volatile
    private var degraded = false

    @Volatile
    private var outstandingPingAt = -1L

    /** Current link generation; bump each time a socket opens. */
    val currentGeneration: Long get() = generation.get()

    /** Sequence number of the most recently encoded outbound message. */
    fun currentSequence(): Long = sequence.get()

    fun start() {
        if (!active.compareAndSet(false, true)) return
        open()
    }

    fun stop(reason: String = "client stop") {
        if (!active.getAndSet(false)) return
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        runCatching { socket?.close(1000, reason) }
        socket = null
        connectedFlow.value = false
    }

    fun send(message: SessionMessage): Boolean {
        val text = codec.encode(message, settings.sessionId, sequence.incrementAndGet())
        return socket?.send(text) ?: false
    }

    /** Telemetry is allowed to be lost; it never gates control traffic. */
    fun sendTelemetry(message: SessionMessage): Boolean = send(message)

    fun sendBinary(bytes: ByteArray): Boolean = socket?.send(okio.ByteString.of(*bytes)) ?: false

    private fun open() {
        if (!active.get()) return
        val url = buildString {
            append(settings.wsUrl)
            append(if (settings.wsUrl.contains('?')) '&' else '?')
            append("session=").append(settings.sessionId)
            append("&role=").append(settings.role)
            append("&token=").append(settings.token)
        }
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, listener())
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!active.get()) {
                webSocket.close(1000, "stopped")
                return
            }
            // A reconnect can race an old socket's late onOpen. Once a newer
            // socket owns the link, the old one is closed instead of taking it
            // over and bumping the generation under the session layer.
            if (connectedFlow.value && socket !== webSocket) {
                webSocket.close(1000, "superseded")
                return
            }
            socket = webSocket
            missedPings = 0
            degraded = false
            val newGeneration = generation.incrementAndGet()
            connectedFlow.value = true
            eventsFlow.tryEmit(TransportEvent.Connected(newGeneration))
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            when (val decoded = codec.decode(text)) {
                is DecodeResult.Ok -> {
                    val message = decoded.message
                    if (message is Pong) {
                        val sentAt = outstandingPingAt
                        if (sentAt > 0) {
                            val rtt = (clock() - sentAt).coerceAtLeast(0)
                            val previous = rttFlow.value.takeIf { it >= 0 }
                            rttFlow.value = if (previous == null) rtt else (previous * 3 + rtt) / 4
                            jitterFlow.value = if (previous == null) 0 else abs(rtt - previous)
                        }
                        missedPings = 0
                        if (degraded) {
                            degraded = false
                            eventsFlow.tryEmit(
                                TransportEvent.Rtt(rttFlow.value, jitterFlow.value, 0),
                            )
                        }
                    }
                    eventsFlow.tryEmit(TransportEvent.Received(decoded.envelope, message))
                }

                is DecodeResult.Malformed -> {
                    // Malformed traffic is logged by the caller; never fatal.
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (socket !== webSocket) return
            eventsFlow.tryEmit(TransportEvent.BinaryReceived(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDrop(webSocket, t.message ?: "socket failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDrop(webSocket, reason.ifBlank { "closed" })
        }
    }

    private fun handleDrop(dropped: WebSocket, reason: String) {
        // Ignore callbacks from a socket that was already replaced. Without
        // this a late failure on the old link clears the healthy new one and
        // makes the session reconnect in a loop.
        if (socket !== dropped) return
        if (socket !== null || connectedFlow.value) {
            connectedFlow.value = false
            eventsFlow.tryEmit(TransportEvent.Disconnected(reason))
        }
        socket = null
        heartbeatJob?.cancel()
        scheduleReconnect()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connectedFlow.value) {
                delay(heartbeatMs)
                if (!connectedFlow.value) break
                outstandingPingAt = clock()
                if (!send(Ping(clock()))) break
                missedPings++
                if (missedPings == degradedAfterMissedPings && !degraded) {
                    degraded = true
                    eventsFlow.tryEmit(
                        TransportEvent.Degraded(rttFlow.value, missedPings),
                    )
                } else if (missedPings > 0) {
                    eventsFlow.tryEmit(
                        TransportEvent.Rtt(rttFlow.value, jitterFlow.value, missedPings),
                    )
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!active.get()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delays = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
            var attempt = 0
            while (active.get() && !connectedFlow.value) {
                delay(delays[attempt.coerceAtMost(delays.lastIndex)])
                attempt++
                if (!active.get()) break
                if (!connectedFlow.value) open()
            }
        }
    }
}
