package com.overlay.net

import java.io.BufferedReader
import java.io.PrintStream

/**
 * One HTTP/1.1 policy for every loopback server in the ecosystem.
 *
 * The three local servers (`agent-support` :8765 and :8766, `vault-a` :8799)
 * each grew their own request parser, bearer extraction and response writer.
 * That is how one of them ended up accepting an `X-Ecosystem-Token` header the
 * others did not, and how body-size limits drifted. This is the shared,
 * dependency-free core they can agree on.
 *
 * It is deliberately small: request-line + headers + response writing, not a
 * router. The routing stays in each app because the routes are the app. When a
 * full server is warranted, Ktor (CIO) is the OSS replacement for the socket
 * loop; this object keeps the policy identical in the meantime.
 */
object LocalHttp {

    /** Accepted request body size; larger payloads are rejected with 413. */
    const val MAX_BODY_BYTES: Int = 1_048_576

    /** Maximum accepted request-line + header line length. */
    const val MAX_LINE_BYTES: Int = 8_192

    data class RequestLine(
        val method: String,
        val path: String,
        val query: String,
    )

    /**
     * Parse `METHOD /path?query HTTP/1.1`. Returns null for a blank or
     * malformed line so callers can drop the connection instead of guessing.
     */
    fun parseRequestLine(line: String?): RequestLine? {
        val trimmed = line?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val target = parts[1]
        return RequestLine(
            method = parts[0].uppercase(),
            path = target.substringBefore('?'),
            query = if ('?' in target) target.substringAfter('?') else "",
        )
    }

    /**
     * Read header lines until the blank separator. Header names are
     * lower-cased so lookups are case-insensitive without a case-insensitive
     * map implementation.
     */
    fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    /** Declared body length, clamped to a non-negative Int. */
    fun contentLength(headers: Map<String, String>): Int =
        headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * The ecosystem's one bearer convention: `Authorization: Bearer <token>`,
     * with `X-Ecosystem-Token` kept as a compatibility fallback.
     */
    fun extractBearer(headers: Map<String, String>): String? =
        headers["authorization"]?.trim()?.let {
            if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it
        } ?: headers["x-ecosystem-token"]

    /** True for the addresses a loopback-only server is allowed to bind. */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().removeSurrounding("[", "]").lowercase()
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    /**
     * Write a `Connection: close` response. Extra headers (CORS, WWW-Authenticate)
     * are emitted verbatim; callers own their policy.
     */
    fun writeResponse(
        out: PrintStream,
        status: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        headers: Map<String, String> = emptyMap(),
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        out.print("HTTP/1.1 $status ${statusText(status)}\r\n")
        out.print("Content-Type: $contentType\r\n")
        out.print("Content-Length: ${bodyBytes.size}\r\n")
        for ((name, value) in headers) out.print("$name: $value\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }

    /** Minimal JSON error body without pulling a JSON dependency into core. */
    fun jsonError(message: String): String = "{\"error\":\"${escapeJson(message)}\"}"

    fun escapeJson(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
