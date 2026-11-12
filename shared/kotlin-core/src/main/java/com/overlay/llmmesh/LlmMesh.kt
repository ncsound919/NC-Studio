package com.overlay.llmmesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Overlay LLM mesh — Android client.
 *
 * The same contract as clients/ts/llmMesh.ts: one base URL, one app id, one
 * bearer token, and the same `/v1/...` routes for every Android app. Blocking on
 * purpose; call it from `Dispatchers.IO`.
 *
 * This file is copied into each Android app by `scripts/sync-clients.mjs`.
 * Edit the canonical copy here, never the copies.
 */

data class MeshConfig(
    val baseUrl: String,
    val appId: String,
    val token: String? = null,
    val timeoutMs: Int = 120_000,
)

data class MeshMessage(val role: String, val content: String)

data class MeshQuestion(
    val name: String,
    val type: String, // CHOICE | NOUL | SCORE
    val instructions: String,
    val criteria: Map<String, String> = emptyMap(),
)

data class MeshDecision(
    val backend: String,
    val fallbackReason: String?,
    val answers: JSONObject?,
    val confidence: Double?,
    val lane: String?,
)

class MeshException(
    message: String,
    val status: Int = 0,
    val code: String = "mesh_error",
    val body: String? = null,
) : Exception(message)

class LlmMeshClient(private val config: MeshConfig) {

    private fun endpoint(path: String): URL =
        URL(config.baseUrl.trimEnd('/') + path)

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (endpoint(path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = minOf(config.timeoutMs, 15_000)
            readTimeout = config.timeoutMs
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-llm-app", config.appId)
            if (config.token != null) {
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input)).use { reader -> reader.readText() }
            } ?: ""
            val parsed = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse {
                throw MeshException("mesh returned non-JSON", status, "invalid_json", text.take(500))
            }
            if (status !in 200..299) {
                val error = parsed.optJSONObject("error")
                throw MeshException(
                    error?.optString("message") ?: "mesh returned $status",
                    status,
                    error?.optString("code") ?: "mesh_error",
                    text.take(500),
                )
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    fun health(): JSONObject = request("GET", "/health")

    fun models(): JSONObject = request("GET", "/v1/models")

    fun route(
        messages: List<MeshMessage> = emptyList(),
        task: String? = null,
        privacy: String? = null,
    ): JSONObject {
        val body = JSONObject().apply {
            if (messages.isNotEmpty()) put("messages", messages.toJson())
            if (task != null) put("task", task)
            if (privacy != null) put("privacy", privacy)
        }
        return request("POST", "/v1/route", body)
    }

    fun chatRaw(
        messages: List<MeshMessage>,
        task: String? = null,
        privacy: String? = null,
        maxTokens: Int = 512,
        temperature: Double = 0.3,
    ): JSONObject {
        val body = JSONObject().apply {
            put("messages", messages.toJson())
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (task != null) put("task", task)
            if (privacy != null) put("privacy", privacy)
        }
        return request("POST", "/v1/chat/completions", body)
    }

    fun chat(
        messages: List<MeshMessage>,
        task: String? = null,
        privacy: String? = null,
        maxTokens: Int = 512,
        temperature: Double = 0.3,
    ): String {
        val data = chatRaw(messages, task, privacy, maxTokens, temperature)
        return data.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: ""
    }

    fun decide(
        questions: List<MeshQuestion>,
        context: JSONObject = JSONObject(),
        confidenceFloor: Double = 0.6,
    ): MeshDecision {
        val questionArray = JSONArray()
        for (question in questions) {
            questionArray.put(JSONObject().apply {
                put("name", question.name)
                put("type", question.type)
                put("instructions", question.instructions)
                if (question.criteria.isNotEmpty()) {
                    put("criteria", JSONObject().apply {
                        for ((key, value) in question.criteria) put(key, value)
                    })
                }
            })
        }
        val body = JSONObject().apply {
            put("questions", questionArray)
            put("context", context)
            put("confidenceFloor", confidenceFloor)
        }
        val data = request("POST", "/v1/decisions", body)
        return MeshDecision(
            backend = data.optString("backend", "rules"),
            fallbackReason = if (data.isNull("fallbackReason")) null else data.optString("fallbackReason"),
            answers = data.optJSONObject("answers"),
            confidence = if (data.has("confidence") && !data.isNull("confidence")) data.optDouble("confidence") else null,
            lane = if (data.isNull("lane")) null else data.optString("lane"),
        )
    }

    fun recall(query: String, k: Int = 8): JSONObject =
        request("POST", "/v1/recall", JSONObject().put("query", query).put("k", k))

    fun context(query: String, maxTokens: Int = 1500): JSONObject =
        request("POST", "/v1/ecosystem/context", JSONObject().put("query", query).put("max_tokens", maxTokens))

    fun event(kind: String, props: JSONObject = JSONObject()): JSONObject =
        request("POST", "/v1/ecosystem/event", JSONObject().put("kind", kind).put("props", props))

    fun feedback(lane: String, ok: Boolean, latencyMs: Long = 0): JSONObject =
        request("POST", "/v1/feedback", JSONObject().put("lane", lane).put("ok", ok).put("latencyMs", latencyMs))

    private fun List<MeshMessage>.toJson(): JSONArray = JSONArray().also { array ->
        for (message in this) {
            array.put(JSONObject().put("role", message.role).put("content", message.content))
        }
    }
}
