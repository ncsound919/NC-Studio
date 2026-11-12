package com.overlay.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the ecosystem memory service (shared/memory/mem0_service.py).
 *
 * Blocking on purpose; call from Dispatchers.IO. Speak the same contract as the
 * Python service: POST /v1/memory, POST /v1/memory/search, GET /v1/memory,
 * DELETE /v1/memory/{id}. Memory is keyed by user_id (+ optional agent_id) and
 * reconciled server-side, so every app sees the same facts.
 */
data class MemoryConfig(
    val baseUrl: String = "http://127.0.0.1:8789",
    val timeoutMs: Int = 30_000,
)

data class MemoryRecord(
    val id: String,
    val memory: String,
    val score: Double? = null,
    val userId: String? = null,
    val createdAt: String? = null,
)

class MemoryException(
    message: String,
    val status: Int = 0,
    val code: String = "memory_error",
    val body: String? = null,
) : Exception(message)

class Mem0Client(private val config: MemoryConfig) {

    private fun endpoint(path: String): URL =
        URL(config.baseUrl.trimEnd('/') + path)

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (endpoint(path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = minOf(config.timeoutMs, 10_000)
            readTimeout = config.timeoutMs
            doInput = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw MemoryException("memory $method $path failed: $status", status, "memory_error", text)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    fun add(text: String, userId: String, agentId: String? = null, metadata: JSONObject? = null): List<MemoryRecord> {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("text", text)
            if (agentId != null) put("agent_id", agentId)
            if (metadata != null) put("metadata", metadata)
        }
        val res = request("POST", "/v1/memory", body)
        val arr = res.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<MemoryRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MemoryRecord(
                id = o.optString("id"),
                memory = o.optString("memory"),
                userId = o.optString("user_id").ifBlank { null },
                createdAt = o.optString("created_at").ifBlank { null },
            )
        }
        return out
    }

    fun search(query: String, userId: String, limit: Int = 5): List<MemoryRecord> {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("query", query)
            put("limit", limit)
        }
        val res = request("POST", "/v1/memory/search", body)
        val arr = res.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<MemoryRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MemoryRecord(
                id = o.optString("id"),
                memory = o.optString("memory"),
                score = if (o.has("score")) o.optDouble("score") else null,
                userId = o.optString("user_id").ifBlank { null },
                createdAt = o.optString("created_at").ifBlank { null },
            )
        }
        return out
    }

    fun all(userId: String, agentId: String? = null): List<MemoryRecord> {
        val path = buildString {
            append("/v1/memory?user_id=").append(urlEncode(userId))
            if (agentId != null) append("&agent_id=").append(urlEncode(agentId))
        }
        val res = request("GET", path)
        val arr = res.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<MemoryRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MemoryRecord(
                id = o.optString("id"),
                memory = o.optString("memory"),
                userId = o.optString("user_id").ifBlank { null },
                createdAt = o.optString("created_at").ifBlank { null },
            )
        }
        return out
    }

    fun delete(id: String) {
        request("DELETE", "/v1/memory/$id")
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
