package com.overlay.tondo

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class TondoResearchTest {

    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    /** Start a loopback server; returns its base URL. */
    private fun serve(
        status: Int,
        response: String,
        capture: AtomicReference<Pair<String, Map<String, String>>>,
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val headers = exchange.requestHeaders.entries.associate { (key, value) ->
                key.lowercase() to value.first()
            }
            capture.set(body to headers)
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    @Test
    fun `sends the app identity and parses typed findings`() {
        val seen = AtomicReference<Pair<String, Map<String, String>>>("" to emptyMap())
        val baseUrl = serve(
            200,
            """
            {
              "requestId": "req-1",
              "app": "concordance",
              "operation": "oncology evidence review",
              "question": "Why do the endpoints disagree?",
              "status": "resolved",
              "summary": "The analysis sets differ.",
              "findings": [
                {"claim": "The table uses the per-protocol set.", "support": "E1", "confidence": 0.82, "kind": "fact"},
                {"claim": "A definition change is likely.", "support": "model_prior", "confidence": 0.55, "kind": "mechanism"}
              ],
              "actions": [{"step": "Recompute on one analysis set.", "why": "The comparison mixes populations.", "risk": "low"}],
              "unresolved": ["Which set was prespecified."],
              "citations": [{"evidenceId": "E1", "title": "Table 2", "url": "https://example.org/table-2"}],
              "escalate": false,
              "lane": "mesh",
              "model": "qwen",
              "adapter": null,
              "overlay": {"latencyMs": 42}
            }
            """.trimIndent(),
            seen,
        )
        val client = TondoResearchClient(
            TondoResearchConfig(baseUrl, "concordance", "device-scoped-key", 5_000),
        )

        val result = client.assist(
            operation = "oncology evidence review",
            question = "Why do the endpoints disagree?",
            symptom = "the agent cannot reconcile the subgroup results",
            evidence = listOf(
                TondoEvidence("E1", "Table 2", "Per-protocol, n=412.", "https://example.org/table-2"),
            ),
        )

        assertEquals("resolved", result.status)
        assertEquals("concordance", result.app)
        assertEquals(2, result.findings.size)
        assertEquals("E1", result.findings[0].support)
        assertEquals(0.82, result.findings[0].confidence, 0.0001)
        assertEquals("low", result.actions[0].risk)
        assertEquals("https://example.org/table-2", result.citations[0].url)
        assertNull(result.adapter, "a JSON null adapter must not become the string 'null'")
        assertEquals(42L, result.latencyMs)
        assertTrue(result.toPlainText().contains("Tondo resolved"))
        assertTrue(result.toPlainText().contains("The table uses the per-protocol set"))
        assertTrue(result.toPlainText().contains("82%"))

        val (body, headers) = seen.get()
        assertTrue(body.contains("\"app\":\"concordance\""))
        assertTrue(body.contains("\"data_class\":\"internal\""))
        assertTrue(body.contains("\"id\":\"E1\""))
        assertEquals("concordance", headers["x-tondo-app"])
        assertEquals("Bearer device-scoped-key", headers["authorization"])
    }

    @Test
    fun `sends a reason turn and parses its lane attribution`() {
        val seen = AtomicReference<Pair<String, Map<String, String>>>("" to emptyMap())
        val baseUrl = serve(
            200,
            """
            {
              "content": "The harness forces one query per round [S1].",
              "lane": "nvidia",
              "model": "nvidia/nemotron-3-ultra-550b-a55b",
              "deep": true,
              "usage": {"input": 114, "output": 210}
            }
            """.trimIndent(),
            seen,
        )
        val client = TondoResearchClient(TondoResearchConfig(baseUrl, "tondo", "key", 5_000))

        val reply = client.reason(
            messages = listOf(
                TondoMessage("system", "Use only the evidence."),
                TondoMessage("user", "Explain the harness [S1]."),
            ),
            maxTokens = 900,
            deep = true,
        )

        assertEquals("nvidia", reply.lane)
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", reply.model)
        assertTrue(reply.deep)
        assertEquals(114, reply.inputTokens)
        assertEquals(210, reply.outputTokens)
        assertTrue(reply.content.contains("[S1]"))

        val (body, headers) = seen.get()
        assertTrue(body.contains("\"deep\":true"))
        assertTrue(body.contains("\"maxTokens\":900"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertEquals("tondo", headers["x-tondo-app"])
        assertEquals("Bearer key", headers["authorization"])
    }

    @Test
    fun `surfaces the structured error instead of a stack trace`() {
        val seen = AtomicReference<Pair<String, Map<String, String>>>("" to emptyMap())
        val baseUrl = serve(
            422,
            """{"error":"phi_not_allowed","detail":"de-identify the question or use the on-device lane"}""",
            seen,
        )
        val client = TondoResearchClient(TondoResearchConfig(baseUrl, "concordance", "key"))

        val error = assertThrows(TondoResearchException::class.java) {
            client.assist("review", "patient question", dataClass = "phi")
        }
        assertEquals(422, error.status)
        assertEquals("phi_not_allowed", error.code)
        assertTrue(error.message!!.contains("de-identify"))
    }

    @Test
    fun `refuses to call when the app has no endpoint or key`() {
        val client = TondoResearchClient(TondoResearchConfig("", "concordance", ""))
        assertFalse(client.isConfigured)
        val error = assertThrows(TondoResearchException::class.java) {
            client.assist("review", "question")
        }
        assertEquals("not_configured", error.code)
    }

    @Test
    fun `rejects empty questions before spending a request`() {
        val client = TondoResearchClient(TondoResearchConfig("http://127.0.0.1:1", "concordance", "key"))
        val error = assertThrows(TondoResearchException::class.java) {
            client.assist("review", "   ")
        }
        assertEquals("question_required", error.code)
        assertNotNull(error.message)
    }
}
