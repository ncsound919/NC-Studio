package com.overlay.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * One WebSocket connection to an NCSound relay, with the reconnect/backoff and
 * Origin rules the Android side needs:
 *
 * - OkHttp sends no `Origin` header, and the reference Node relay rejects
 *   origin-less handshakes from non-loopback peers. The embedded Kotlin relay
 *   does not check Origin, but sending it keeps the client compatible with
 *   both relays.
 * - Cleartext `ws://` is used on a trusted LAN; `wss://` works unchanged.
 * - Reconnect uses exponential backoff (400 ms × 2ⁿ, capped), reset on open.
 */
class RemoteSocket(
    private val url: String,
    private val origin: String? = null,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onFailure: (Throwable?) -> Unit,
    private val onClosed: (Int, String) -> Unit,
    private val client: OkHttpClient = defaultClient(),
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closedByUs = false
    @Volatile private var attempts = 0
    @Volatile private var opened = false
    private val queue = ArrayDeque<String>()
    private var reconnectThread: Thread? = null

    fun connect() {
        closedByUs = false
        open()
    }

    private fun open() {
        val builder = Request.Builder().url(url)
        if (origin != null) builder.header("Origin", origin)
        socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                opened = true
                // Frames queued while the handshake was in flight go out now;
                // OkHttp drops sends issued before onOpen.
                val pending: List<String>
                synchronized(queue) {
                    pending = queue.toList()
                    queue.clear()
                }
                pending.forEach { webSocket.send(it) }
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened = false
                if (closedByUs) return
                onFailure(t)
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Relay-initiated closes are terminal for a seat; let the caller
                // decide via onClosed whether to re-join.
                onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closedByUs) return
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByUs) return
        attempts = (attempts + 1).coerceAtMost(6)
        val delayMs = (400L shl (attempts - 1)).coerceAtMost(8_000L)
        if (reconnectThread?.isAlive == true) return
        reconnectThread = Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closedByUs) open()
        }.apply { isDaemon = true; start() }
    }

    /** Sends a frame, queueing it until the socket opens. */
    fun send(text: String): Boolean {
        val ws = socket ?: return false
        return if (opened) {
            ws.send(text)
        } else {
            synchronized(queue) { queue.addLast(text) }
            true
        }
    }

    fun close() {
        closedByUs = true
        opened = false
        socket?.close(1000, "bye")
        socket = null
        synchronized(queue) { queue.clear() }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
