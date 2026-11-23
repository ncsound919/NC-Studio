package com.ncstudio.daw.audio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalModelDetectorTest {

    @Test
    fun testOllamaTagsJsonParsing() {
        val sampleOllamaJson = """
            {
              "models": [
                {
                  "name": "llama3.2:1b",
                  "modified_at": "2024-10-20T12:00:00Z",
                  "size": 1300000000,
                  "details": {
                    "family": "llama",
                    "parameter_size": "1.3B"
                  }
                },
                {
                  "name": "qwen2.5:0.5b",
                  "modified_at": "2024-10-21T08:30:00Z",
                  "size": 520000000,
                  "details": {
                    "family": "qwen2",
                    "parameter_size": "0.5B"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsedModels = LocalModelDetector.parseOllamaTagsResponse(sampleOllamaJson, "http://10.0.2.2:11434")

        assertEquals(2, parsedModels.size)
        val m1 = parsedModels[0]
        assertEquals("llama3.2:1b", m1.name)
        assertEquals(LocalModelProvider.OLLAMA, m1.provider)
        assertEquals("http://10.0.2.2:11434", m1.baseUrl)
        assertEquals("1.3B", m1.parameterSize)
        assertEquals("llama", m1.family)

        val m2 = parsedModels[1]
        assertEquals("qwen2.5:0.5b", m2.name)
        assertEquals("0.5B", m2.parameterSize)
    }

    @Test
    fun testLlamaCppModelsJsonParsing() {
        val sampleLlamaCppJson = """
            {
              "object": "list",
              "data": [
                {
                  "id": "phi3-mini-4k-instruct-q4",
                  "object": "model",
                  "created": 1700000000,
                  "owned_by": "llamacpp"
                }
              ]
            }
        """.trimIndent()

        val parsedModels = LocalModelDetector.parseLlamaCppModelsResponse(sampleLlamaCppJson, "http://10.0.2.2:8080")

        assertEquals(1, parsedModels.size)
        val m1 = parsedModels[0]
        assertEquals("phi3-mini-4k-instruct-q4", m1.name)
        assertEquals(LocalModelProvider.LLAMA_CPP, m1.provider)
        assertEquals("http://10.0.2.2:8080", m1.baseUrl)
    }

    @Test
    fun testProbeOfflineEndpointHandledGracefully() {
        val offlineEndpoint = LocalModelEndpoint(
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://127.0.0.1:59999", // Unreachable port
            displayName = "Offline Test Endpoint"
        )

        val result = LocalModelDetector.probeEndpoint(offlineEndpoint)

        assertFalse(result.isOnline)
        assertNotNull(result.errorMessage)
        assertTrue(result.models.isEmpty())
    }

    @Test
    fun offlineGenerationReportsAnErrorInsteadOfInventingAdvice() {
        val model = DiscoveredModelInfo(
            name = "llama3.2:1b",
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://127.0.0.1:59999"
        )

        val reply = LocalModelDetector.generateWithLocalModel(
            model = model,
            prompt = "How to clean vocal mud and adjust high-shelf EQ?"
        )

        assertFalse(reply.isSuccess)
        assertNull(reply.text)
        assertNotNull(reply.error)
        assertTrue(reply.error!!.contains("No response from llama3.2:1b"))
        assertFalse(reply.error!!.contains("vocal clarity"))
    }

    @Test
    fun localTransportRefusesHostsThatAreNotOnTheLocalNetwork() {
        // A public literal address never gets a socket: the transport is the
        // only cleartext path in the app, and it is limited to loopback/LAN.
        assertNull(LocalHttpTransport.get("http://93.184.216.34/health", 200, 200))
        // Loopback is allowed, but nothing is listening on this port.
        assertNull(LocalHttpTransport.get("http://127.0.0.1:59999/health", 200, 200))
    }

    @Test
    fun localTransportReadsAContentLengthBody() {
        val body = """{"models":[]}"""
        val server = serveOnce { socket ->
            readHeaders(socket)
            writeRaw(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body",
            )
        }

        val response = LocalHttpTransport.get("http://127.0.0.1:${server.localPort}/api/tags", 1000, 2000)
        server.close()

        assertEquals(200, response?.code)
        assertEquals(body, response?.body)
    }

    @Test
    fun localTransportReadsAChunkedBody() {
        val server = serveOnce { socket ->
            readHeaders(socket)
            writeRaw(
                socket,
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                    "9\r\n{\"models\"\r\n" +
                    "4\r\n:[]}\r\n" +
                    "0\r\n\r\n",
            )
        }

        val response = LocalHttpTransport.get("http://127.0.0.1:${server.localPort}/v1/models", 1000, 2000)
        server.close()

        assertEquals(200, response?.code)
        assertEquals("""{"models":[]}""", response?.body)
    }

    @Test
    fun localTransportPostsJsonWithAContentLength() {
        val sent = AtomicReference("")
        val body = """{"model":"x"}"""
        val responseBody = """{"ok":true}"""
        val server = serveOnce { socket ->
            sent.set(readHeaders(socket))
            writeRaw(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${responseBody.toByteArray().size}\r\nConnection: close\r\n\r\n$responseBody",
            )
        }

        val response = LocalHttpTransport.postJson(
            "http://127.0.0.1:${server.localPort}/v1/chat/completions",
            body,
            connectTimeoutMs = 1000,
            readTimeoutMs = 2000,
        )
        server.close()

        assertEquals(200, response?.code)
        assertEquals(responseBody, response?.body)
        val request = sent.get()
        assertTrue(request.startsWith("POST /v1/chat/completions HTTP/1.1"))
        assertTrue(request.contains("Content-Type: application/json"))
        assertTrue(request.contains("Content-Length: ${body.toByteArray().size}"))
    }

    private fun serveOnce(handler: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { server.accept().use(handler) }
        return server
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val text = StringBuilder()
        val buffer = ByteArray(1024)
        while (!text.contains("\r\n\r\n")) {
            val read = input.read(buffer)
            if (read < 0) break
            text.append(String(buffer, 0, read))
        }
        return text.toString()
    }

    private fun writeRaw(socket: Socket, raw: String) {
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
    }
}
