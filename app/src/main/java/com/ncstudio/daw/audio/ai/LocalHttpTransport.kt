package com.ncstudio.daw.audio.ai

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plain HTTP/1.1 for local model servers, and for nothing else.
 *
 * Android's network security config can permit cleartext per hostname, but not
 * for a subnet - and the model server on the studio Wi-Fi has a DHCP address
 * that does not exist when the app is built. Opening cleartext for the whole
 * app just to reach it is worse than a narrow transport, so this one refuses
 * every destination that is not loopback, link-local or RFC 1918 *before* it
 * connects. The release network security config keeps cleartext denied for
 * every other host.
 */
internal object LocalHttpTransport {

    data class Response(val code: Int, val body: String)

    fun get(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Response? =
        request("GET", url, null, connectTimeoutMs, readTimeoutMs)

    fun postJson(
        url: String,
        json: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
    ): Response? = request("POST", url, json, connectTimeoutMs, readTimeoutMs, headers)

    /** True only for addresses the studio may speak plain HTTP to. */
    private fun isLocalAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress

    private fun request(
        method: String,
        url: String,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (!isLocalAddress(address)) return null
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Accept: application/json\r\n")
            append("Connection: close\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            if (bodyBytes != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            append("\r\n")
        }

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    if (bodyBytes != null) write(bodyBytes)
                    flush()
                }
                readResponse(BufferedInputStream(socket.getInputStream()))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readResponse(input: InputStream): Response? {
        val statusLine = readLine(input) ?: return null
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
        val bytes = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            contentLength != null -> readExactly(input, contentLength)
            else -> input.readBytes()
        }
        return Response(code, bytes.toString(Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString()
            if (b != '\r'.code) line.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length.coerceAtLeast(0))
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            out.write(readExactly(input, size))
            readLine(input) // CRLF after the chunk payload
        }
        return out.toByteArray()
    }
}