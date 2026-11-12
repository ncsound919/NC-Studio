package com.overlay.net

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalHttpTest {

    @Test
    fun `request line splits method path and query`() {
        val line = assertNotNull(LocalHttp.parseRequestLine("GET /api/memory/blocks?topK=3 HTTP/1.1"))
        assertEquals("GET", line.method)
        assertEquals("/api/memory/blocks", line.path)
        assertEquals("topK=3", line.query)
    }

    @Test
    fun `request line without query keeps an empty query`() {
        val line = assertNotNull(LocalHttp.parseRequestLine("post /setup HTTP/1.1"))
        assertEquals("POST", line.method)
        assertEquals("/setup", line.path)
        assertEquals("", line.query)
    }

    @Test
    fun `malformed request lines are rejected`() {
        assertNull(LocalHttp.parseRequestLine(null))
        assertNull(LocalHttp.parseRequestLine(""))
        assertNull(LocalHttp.parseRequestLine("GET"))
    }

    @Test
    fun `headers are lowercased and stop at the blank line`() {
        val raw = "Content-Type: application/json\r\nAuthorization: Bearer abc123\r\n\r\nignored"
        val headers = LocalHttp.readHeaders(BufferedReader(StringReader(raw)))
        assertEquals("application/json", headers["content-type"])
        assertEquals("Bearer abc123", headers["authorization"])
        assertFalse(headers.containsKey("ignored"))
    }

    @Test
    fun `bearer extraction accepts the standard header and the legacy fallback`() {
        assertEquals("abc123", LocalHttp.extractBearer(mapOf("authorization" to "Bearer abc123")))
        assertEquals("abc123", LocalHttp.extractBearer(mapOf("authorization" to "bearer abc123")))
        assertEquals("legacy", LocalHttp.extractBearer(mapOf("x-ecosystem-token" to "legacy")))
        assertNull(LocalHttp.extractBearer(emptyMap()))
    }

    @Test
    fun `content length is clamped and missing length is zero`() {
        assertEquals(0, LocalHttp.contentLength(emptyMap()))
        assertEquals(0, LocalHttp.contentLength(mapOf("content-length" to "-5")))
        assertEquals(0, LocalHttp.contentLength(mapOf("content-length" to "nope")))
        assertEquals(128, LocalHttp.contentLength(mapOf("content-length" to "128")))
    }

    @Test
    fun `only loopback hosts are accepted`() {
        assertTrue(LocalHttp.isLoopbackHost("127.0.0.1"))
        assertTrue(LocalHttp.isLoopbackHost("localhost"))
        assertTrue(LocalHttp.isLoopbackHost("::1"))
        assertTrue(LocalHttp.isLoopbackHost("[::1]"))
        assertFalse(LocalHttp.isLoopbackHost("0.0.0.0"))
        assertFalse(LocalHttp.isLoopbackHost("192.168.1.10"))
    }

    @Test
    fun `response writer emits status length and extra headers`() {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, "UTF-8")
        LocalHttp.writeResponse(
            out,
            401,
            """{"error":"nope"}""",
            headers = mapOf("WWW-Authenticate" to "Bearer realm=\"test\""),
        )
        val text = bytes.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(text.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertTrue(text.contains("Content-Length: 16\r\n"))
        assertTrue(text.contains("WWW-Authenticate: Bearer realm=\"test\"\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"error\":\"nope\"}"))
    }

    @Test
    fun `json error messages are escaped, not interpolated raw`() {
        assertEquals("""{"error":"a\"b\\c"}""", LocalHttp.jsonError("a\"b\\c"))
        assertEquals("""{"error":"line\nbreak"}""", LocalHttp.jsonError("line\nbreak"))
    }
}
