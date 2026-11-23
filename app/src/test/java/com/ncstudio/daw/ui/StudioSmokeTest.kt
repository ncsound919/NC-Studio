package com.ncstudio.daw.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ncstudio.daw.audio.ai.LocalModelProvider
import com.ncstudio.daw.audio.model.DawSettings
import com.ncstudio.daw.session.net.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One pass through the whole studio path this work touched: local model
 * discovery and inference over the private-address transport, the session
 * address guard, and the blank-session/template/track controls. Every step
 * uses the real production code; only the model server is a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StudioSmokeTest {

    @Test
    fun everySessionFunctionWorksInOnePass() {
        val server = FakeLlamaServer()
        try {
            // ------------------------------------------------- local model
            val state = MutableStateFlow(EngineUiState())
            var settings = DawSettings(customOllamaUrl = server.baseUrl)
            val sweeps = mutableListOf<String>()
            val controller = AiStudioController(
                scope = CoroutineScope(Dispatchers.Unconfined),
                currentSettings = { settings },
                updateSettings = { transform -> settings = transform(settings) },
                uiState = state,
                // A Wi-Fi subnet is available; the automatic scan must ignore it.
                lanSubnetPrefix = { "192.168.1" },
                discoverLanEndpoints = { prefix ->
                    sweeps.add(prefix)
                    emptyList()
                },
            )

            controller.scan(dispatcher = Dispatchers.Unconfined)
            assertTrue("the automatic scan must not sweep the subnet", sweeps.isEmpty())
            val detected = state.value.localModelDetectorState
            assertFalse(detected.isScanning)
            val model = detected.detectedModels.first { it.name == "smoke:1b" }
            assertEquals(LocalModelProvider.LLAMA_CPP, model.provider)

            controller.select(model)
            assertEquals("smoke:1b", settings.activeLocalModelName)
            assertEquals(server.baseUrl, settings.customOllamaUrl)

            // A restart clears the UI but not the saved choice.
            state.update { current ->
                current.copy(localModelDetectorState = current.localModelDetectorState.copy(activeModel = null))
            }
            controller.restoreFromSettings()
            assertEquals("smoke:1b", state.value.localModelDetectorState.activeModel?.name)

            controller.send("How do I clean up a kick drum?", Dispatchers.Unconfined)
            assertEquals(
                "Cut 300 Hz on the kick.",
                state.value.localModelDetectorState.lastInferenceResponse,
            )

        } finally {
            server.close()
        }

        // ------------------------------------------------- remote session
        assertNotNull(ConnectionSettings.cleartextWarning("192.168.1.20:8787"))
        assertNull(ConnectionSettings.cleartextWarning("studio-laptop.local:8787"))
        val connection = ConnectionSettings.fromHost(
            hostInput = "192.168.1.20:8787",
            sessionId = "s",
            role = ConnectionSettings.ROLE_ARTIST,
            token = "t",
            allowCleartext = true,
        )
        assertEquals("http://192.168.1.20:8787/v1", connection.baseUrl)
        assertEquals("ws://192.168.1.20:8787/ws", connection.wsUrl)

        // ------------------------------------------------- session editing
        val application: Application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        File(application.filesDir, "projects").deleteRecursively()
        val viewModel = DawViewModel(application)
        try {
            runBlocking { viewModel.awaitSessionReady() }
            assertEquals(4, viewModel.session.value.tracks.size)
            assertEquals(
                listOf("Audio 1", "Audio 2", "Audio 3", "Audio 4"),
                viewModel.session.value.tracks.map { it.name },
            )

            viewModel.toggleTrackArm(1)
            assertTrue(viewModel.session.value.tracks[1].isArmedForRecording)

            viewModel.applyMixTemplate("pop")
            val templated = viewModel.session.value
            assertTrue("a genre template lays out role tracks", templated.tracks.size > 4)
            assertTrue(templated.tracks.any { it.pluginChain.isNotEmpty() })
            assertTrue(templated.masterPluginChain.isNotEmpty())

            viewModel.undo()
            assertEquals("undo restores the blank four-track session", 4, viewModel.session.value.tracks.size)
            assertTrue(viewModel.session.value.tracks.all { it.pluginChain.isEmpty() })

            viewModel.addTrack()
            assertEquals(5, viewModel.session.value.tracks.size)
            assertEquals("Audio 5", viewModel.session.value.tracks.last().name)
        } finally {
            viewModel.onCleared()
        }
    }
}

/** Minimal llama.cpp-compatible server: health, models and chat completions. */
private class FakeLlamaServer {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use(::respond)
            } catch (_: Exception) {
                return@thread
            }
        }
    }

    private fun respond(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(body, read, contentLength - read)
                if (count < 0) break
                read += count
            }
        }

        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val (code, body) = when (path) {
            "/health" -> 200 to """{"status":"ok"}"""
            "/v1/models" -> 200 to
                """{"object":"list","data":[{"id":"smoke:1b","object":"model"}]}"""
            "/v1/chat/completions" -> 200 to
                """{"choices":[{"message":{"content":"Cut 300 Hz on the kick."}}]}"""
            else -> 404 to """{"error":"not found"}"""
        }
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    fun close() {
        server.close()
        worker.join(1000)
    }
}