package com.example.session.net

import com.example.session.protocol.Arm
import com.example.session.protocol.DecodeResult
import com.example.session.protocol.MessageCodec
import com.example.session.protocol.Ping
import com.example.session.protocol.Pong
import com.example.session.protocol.StartTake
import com.example.session.protocol.StateChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class WebSocketTransportTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val codec = MessageCodec()
    private val received = CopyOnWriteArrayList<String>()
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val serverSeq = AtomicLong(0)
    private val events = CopyOnWriteArrayList<TransportEvent>()
    private lateinit var server: MockWebServer
    private var transport: WebSocketTransport? = null

    private val sid = "session-1"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        transport?.stop()
        serverSockets.forEach { runCatching { it.close(1000, "test over") } }
        Thread.sleep(150)
        runCatching { server.shutdown() }
        scope.cancel()
    }

    private fun upgrade(replyPongs: Boolean = true): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets += webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (replyPongs) {
                    val decoded = codec.decode(text)
                    if (decoded is DecodeResult.Ok && decoded.message is Ping) {
                        val ping = decoded.message as Ping
                        webSocket.send(
                            codec.encode(Pong(ping.sentAtMs, 0), sid, serverSeq.incrementAndGet()),
                        )
                    }
                }
            }
        })

    private fun settings(): ConnectionSettings = ConnectionSettings.fromHost(
        "127.0.0.1:${server.port}",
        sessionId = sid,
        role = "engineer",
        token = "test-token",
    )

    private fun startTransport(
        heartbeatMs: Long = 60_000,
        degradedAfter: Int = 3,
    ): WebSocketTransport {
        val transport = WebSocketTransport(
            settings = settings(),
            http = OkHttpClient(),
            scope = scope,
            codec = codec,
            heartbeatMs = heartbeatMs,
            degradedAfterMissedPings = degradedAfter,
        )
        this.transport = transport
        scope.launch { transport.events.collect { events += it } }
        transport.start()
        return transport
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun commandsGoOutAndEventsComeBack() {
        server.enqueue(upgrade())
        val transport = startTransport()
        assertTrue("transport must connect", await { transport.connected.value })

        assertTrue(transport.send(StartTake("take-1", 960_000)))
        assertTrue("server must receive the command", await { received.any { it.contains("StartTake") } })
        assertTrue("server socket must be open", await { serverSockets.isNotEmpty() })

        serverSockets.first().send(codec.encode(StateChanged("ARMED", 4), sid, 77))

        assertTrue(await { events.any { it is TransportEvent.Received } })
        val event = events.filterIsInstance<TransportEvent.Received>().first()
        assertEquals(4L, (event.message as StateChanged).revision)
    }

    @Test
    fun reconnectGetsANewGenerationAndTheLinkWorksAgain() {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        val transport = startTransport()
        assertTrue(await { transport.connected.value })
        val firstGeneration = transport.currentGeneration
        assertTrue("server socket must be open", await { serverSockets.isNotEmpty() })

        serverSockets.first().close(1000, "server restart")
        assertTrue("client must notice the drop", await { !transport.connected.value })
        assertTrue(
            "client must reconnect on a new generation",
            await(8_000) {
                transport.connected.value && transport.currentGeneration > firstGeneration
            },
        )

        assertTrue("a command on the fresh link must go out", transport.send(Arm(true)))
        assertTrue(await { received.any { it.contains("Arm") } })
    }

    @Test
    fun heartbeatMeasuresRttWhenPongsArrive() {
        server.enqueue(upgrade(replyPongs = true))
        val transport = startTransport(heartbeatMs = 60)
        assertTrue(await { transport.connected.value })
        assertTrue("an RTT must be measured", await { transport.rttMs.value >= 0 })
    }

    @Test
    fun lostTelemetryDoesNotBlockControlTraffic() {
        server.enqueue(upgrade(replyPongs = false))
        val transport = startTransport(heartbeatMs = 50, degradedAfter = 2)
        assertTrue(await { transport.connected.value })

        assertTrue("control must go out even while pongs are missing", transport.send(Arm(true)))
        assertTrue(await { received.any { it.contains("Arm") } })
        assertTrue(
            "a run of missed pongs must mark the link degraded",
            await(2_000) { events.any { it is TransportEvent.Degraded } },
        )
    }

    @Test
    fun malformedInboundDataDoesNotKillTheConnection() {
        server.enqueue(upgrade())
        val transport = startTransport()
        assertTrue(await { transport.connected.value })
        assertTrue("server socket must be open", await { serverSockets.isNotEmpty() })

        serverSockets.first().send("this is not a protocol message")
        serverSockets.first().send("""{"v":1,"sid":"session-1","seq":2,"type":"Nope","body":{}}""")
        serverSockets.first().send(codec.encode(StateChanged("IDLE", 1), sid, 3))

        assertTrue(await { events.any { it is TransportEvent.Received } })
        assertTrue(transport.connected.value)
    }
}
