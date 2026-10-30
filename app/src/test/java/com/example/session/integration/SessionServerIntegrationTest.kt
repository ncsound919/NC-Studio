package com.example.session.integration

import com.example.session.net.BackendApi
import com.example.session.net.ConnectionSettings
import com.example.session.net.TransportEvent
import com.example.session.net.WebSocketTransport
import com.example.session.protocol.Hello
import com.example.session.protocol.PROTOCOL_VERSION
import com.example.session.take.ChunkedTakeWriter
import com.example.session.take.TakeConfig
import com.example.session.take.TakeManifest
import com.example.session.take.TakeStore
import com.example.session.upload.DownloadOutcome
import com.example.session.upload.TakeDownloader
import com.example.session.upload.UploadApi
import com.example.session.upload.UploadEngine
import com.example.session.upload.UploadOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * End-to-end check against the real reference server: the app's own client
 * classes (BackendApi, WebSocketTransport, UploadEngine, TakeDownloader) talk
 * to `session-server/server.js` over loopback.
 *
 * Skipped when Node or the server's dependencies are missing, so the suite
 * still runs on machines without them.
 */
class SessionServerIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var serverProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
        serverProcess?.destroy()
        serverProcess?.waitFor(5, TimeUnit.SECONDS)
    }

    @Test
    fun artistUploadsATakeThatTheEngineerDownloadsAndVerifies() {
        val serverDir = findServerDir()
        val port = freePort()
        val dataDir = temp.newFolder("server-data")
        startServer(serverDir, port, dataDir)

        val backendUrl = "http://127.0.0.1:$port/v1"
        val backend = BackendApi(backendUrl, OkHttpClient())

        val engineer = backend.createSession("engineer", "Engineer")
        val artist = backend.joinSession(engineer.inviteCode!!, "Artist")
        assertEquals(engineer.sessionId, artist.sessionId)

        // --- control channel: the engineer's Hello reaches the artist -------------
        val engineerTransport = WebSocketTransport(
            ConnectionSettings.fromHost("127.0.0.1:$port", engineer.sessionId, "engineer", engineer.token),
            OkHttpClient(),
            scope,
        )
        val artistTransport = WebSocketTransport(
            ConnectionSettings.fromHost("127.0.0.1:$port", artist.sessionId, "artist", artist.token),
            OkHttpClient(),
            scope,
        )
        // Collect before sending: transport events are live, not replayed.
        val artistEvents = CopyOnWriteArrayList<TransportEvent>()
        scope.launch { artistTransport.events.collect { artistEvents += it } }
        engineerTransport.start()
        artistTransport.start()
        assertTrue(await { engineerTransport.connected.value && artistTransport.connected.value })
        assertTrue(engineerTransport.send(Hello(PROTOCOL_VERSION, "engineer", emptyList())))

        // Heartbeat pings also arrive as messages, so wait for the Hello itself.
        assertTrue(
            "the artist must receive the engineer's Hello",
            await(5_000) {
                artistEvents.any { it is TransportEvent.Received && it.message is Hello }
            },
        )

        // --- the artist records a take, uploads it, server verifies it ------------
        val artistStore = TakeStore(temp.newFolder("artist"))
        val manifest = writeTake(artistStore, artist.sessionId)
        val artistApi = UploadApi(backendUrl, OkHttpClient(), { artist.token })
        val outcome = UploadEngine(artistApi, artistStore).upload(manifest)
        assertTrue("upload outcome was $outcome", outcome is UploadOutcome.Verified)

        val uploaded = File(dataDir, "takes/${artist.sessionId}/${manifest.takeId}/chunks/0.part")
        assertTrue("server must store the chunk", uploaded.exists())

        // --- the engineer downloads, re-hashes and verifies ----------------------
        val engineerStore = TakeStore(temp.newFolder("engineer"))
        val engineerApi = UploadApi(backendUrl, OkHttpClient(), { engineer.token })
        val download = TakeDownloader(engineerApi, engineerStore).download(manifest)
        assertTrue("download outcome was $download", download is DownloadOutcome.Verified)
        assertTrue(engineerStore.verifyAgainstManifest(manifest).ok)
    }

    private fun writeTake(store: TakeStore, sessionId: String): TakeManifest {
        val writer = ChunkedTakeWriter(
            store = store,
            config = TakeConfig(takeId = "take-e2e", sessionId = sessionId, trackId = 0),
            chunkTargetBytes = 48_000, // 1 s chunks keep the test quick
        )
        assertTrue(writer.begin())
        val frames = 90_000 // 1.875 s
        val samples = FloatArray(frames) { (it % 100) / 100f - 0.5f }
        writer.write(samples, frames)
        val manifest = writer.finish()
        assertNotNull(manifest)
        return manifest!!
    }

    private fun findServerDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "../session-server"),
            File(System.getProperty("user.dir"), "session-server"),
        )
        val serverDir = candidates.firstOrNull { File(it, "server.js").exists() }
        assumeTrue("reference server sources not found", serverDir != null)
        assumeTrue(
            "run `npm install` in session-server first",
            File(serverDir!!, "node_modules/ws").exists(),
        )
        return serverDir
    }

    private fun startServer(serverDir: File, port: Int, dataDir: File) {
        val process = ProcessBuilder("node", "server.js")
            .directory(serverDir)
            .redirectErrorStream(true)
            .redirectOutput(File(temp.root, "server.log"))
            .apply {
                environment()["PORT"] = port.toString()
                environment()["DATA_DIR"] = dataDir.absolutePath
            }
            .start()
        serverProcess = process

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (probe("http://127.0.0.1:$port/v1/probe")) return
            if (!process.isAlive) break
            Thread.sleep(150)
        }
        val log = File(temp.root, "server.log").readText().takeLast(500)
        throw AssertionError("session server did not come up. log:\n$log")
    }

    private fun probe(url: String): Boolean = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 1_000
            readTimeout = 1_000
            setFixedLengthStreamingMode(16)
            outputStream.use { it.write(ByteArray(16)) }
            val code = responseCode
            disconnect()
            code == 204
        }
    }.getOrDefault(false)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
