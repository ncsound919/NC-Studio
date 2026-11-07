package com.ncstudio.daw.audio.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class LocalModelProvider(val displayName: String, val defaultPort: Int) {
    OLLAMA("Ollama REST", 11434),
    LLAMA_CPP("Llama.cpp Server", 8080),
    OPEN_AI_REST("OpenAI REST API", 8000)
}

data class LocalModelEndpoint(
    val provider: LocalModelProvider,
    val baseUrl: String,
    val displayName: String
)

data class DiscoveredModelInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val provider: LocalModelProvider,
    val baseUrl: String,
    val parameterSize: String = "",
    val family: String = "",
    val sizeBytes: Long = 0L,
    val isSelected: Boolean = false
)

data class EndpointProbeResult(
    val endpoint: LocalModelEndpoint,
    val isOnline: Boolean,
    val latencyMs: Long = 0L,
    val version: String = "",
    val models: List<DiscoveredModelInfo> = emptyList(),
    val errorMessage: String? = null
)

data class LocalModelDetectorState(
    val isScanning: Boolean = false,
    val endpointsChecked: Int = 0,
    val totalEndpointsToScan: Int = 0,
    val probedEndpoints: List<EndpointProbeResult> = emptyList(),
    val detectedModels: List<DiscoveredModelInfo> = emptyList(),
    val activeModel: DiscoveredModelInfo? = null,
    val customEndpointUrl: String = "http://10.0.2.2:11434",
    val lastScanTimestamp: Long = 0L,
    val lastInferenceResponse: String? = null,
    val isInferenceRunning: Boolean = false
)

/**
 * Outcome of a local-model request. A failure is an error, never prose that
 * looks like mix advice: the app must not attribute fabricated guidance to a
 * model that never answered.
 */
data class LocalModelReply(
    val text: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = text != null
}

object LocalModelDetector {

    // The gateway resolves this app's adapter from shared/llm-mesh/models.json.
    // Direct llama.cpp/Ollama servers ignore the headers.
    private const val MESH_APP_ID = "nc-studio"
    private const val MESH_TASK = "audio-mix"
    private const val GENERATION_READ_TIMEOUT_MS = 120_000

    // Standard local server endpoints to probe. The mesh is first: it is the
    // app-wide entry point and switches the adapter by x-llm-app, so a scan
    // selects it before a direct llama.cpp/Ollama instance.
    val DEFAULT_ENDPOINTS = listOf(
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://127.0.0.1:8788", "Overlay mesh (Localhost 127.0.0.1:8788)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://10.0.2.2:8788", "Overlay mesh (Emulator Host 10.0.2.2:8788)"),
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://10.0.2.2:11434", "Ollama (Emulator Host 10.0.2.2:11434)"),
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://127.0.0.1:11434", "Ollama (Localhost 127.0.0.1:11434)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://10.0.2.2:8080", "Llama.cpp (Emulator Host 10.0.2.2:8080)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://127.0.0.1:8080", "Llama.cpp (Localhost 127.0.0.1:8080)")
    )

    fun parseOllamaTagsResponse(jsonString: String, baseUrl: String): List<DiscoveredModelInfo> {
        val list = mutableListOf<DiscoveredModelInfo>()
        try {
            val root = JSONObject(jsonString)
            val modelsArray = root.optJSONArray("models") ?: JSONArray()
            for (i in 0 until modelsArray.length()) {
                val obj = modelsArray.getJSONObject(i)
                val modelName = obj.optString("name", "unknown")
                val sizeBytes = obj.optLong("size", 0L)
                val details = obj.optJSONObject("details")
                val family = details?.optString("family", "llama") ?: "llama"
                val paramSize = details?.optString("parameter_size", "1B") ?: "1B"

                list.add(
                    DiscoveredModelInfo(
                        name = modelName,
                        provider = LocalModelProvider.OLLAMA,
                        baseUrl = baseUrl,
                        parameterSize = paramSize,
                        family = family,
                        sizeBytes = sizeBytes
                    )
                )
            }
        } catch (e: Exception) {
            // Silently handle parse errors
        }
        return list
    }

    fun parseLlamaCppModelsResponse(jsonString: String, baseUrl: String): List<DiscoveredModelInfo> {
        val list = mutableListOf<DiscoveredModelInfo>()
        try {
            val root = JSONObject(jsonString)
            val dataArray = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val modelId = obj.optString("id", "llama-model")
                list.add(
                    DiscoveredModelInfo(
                        name = modelId,
                        provider = LocalModelProvider.LLAMA_CPP,
                        baseUrl = baseUrl,
                        parameterSize = "Small",
                        family = "gguf"
                    )
                )
            }
        } catch (e: Exception) {
            // Silently handle parse errors
        }
        return list
    }

    fun probeEndpoint(endpoint: LocalModelEndpoint): EndpointProbeResult {
        val startTime = System.currentTimeMillis()
        return try {
            val isOllama = endpoint.provider == LocalModelProvider.OLLAMA
            val versionUrlStr = if (isOllama) "${endpoint.baseUrl}/api/version" else "${endpoint.baseUrl}/health"
            val versionUrl = URL(versionUrlStr)

            val conn = (versionUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 3000
                doInput = true
            }

            val code = conn.responseCode
            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime

            if (code in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val versionText = reader.use { it.readText() }
                conn.disconnect()

                // Fetch models
                val modelsUrlStr = if (isOllama) "${endpoint.baseUrl}/api/tags" else "${endpoint.baseUrl}/v1/models"
                val modelsUrl = URL(modelsUrlStr)
                val mConn = (modelsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 3000
                    doInput = true
                }

                val mCode = mConn.responseCode
                val models = if (mCode in 200..299) {
                    val mReader = BufferedReader(InputStreamReader(mConn.inputStream))
                    val mJson = mReader.use { it.readText() }
                    if (isOllama) parseOllamaTagsResponse(mJson, endpoint.baseUrl)
                    else parseLlamaCppModelsResponse(mJson, endpoint.baseUrl)
                } else emptyList()
                mConn.disconnect()

                EndpointProbeResult(
                    endpoint = endpoint,
                    isOnline = true,
                    latencyMs = latency,
                    version = versionText.take(50),
                    models = models
                )
            } else {
                conn.disconnect()
                EndpointProbeResult(
                    endpoint = endpoint,
                    isOnline = false,
                    latencyMs = latency,
                    errorMessage = "HTTP $code"
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            EndpointProbeResult(
                endpoint = endpoint,
                isOnline = false,
                latencyMs = latency,
                errorMessage = e.message ?: "Connection refused"
            )
        }
    }

    fun generateWithLocalModel(
        model: DiscoveredModelInfo,
        prompt: String,
        systemPrompt: String = "You are a tactical audio engineer AI assistant."
    ): LocalModelReply {
        return try {
            if (model.provider == LocalModelProvider.OLLAMA) {
                val url = URL("${model.baseUrl}/api/generate")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = GENERATION_READ_TIMEOUT_MS
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-llm-app", MESH_APP_ID)
                    setRequestProperty("x-llm-task", MESH_TASK)
                }

                val body = JSONObject().apply {
                    put("model", model.name)
                    put("prompt", "$systemPrompt\n\nUser Question: $prompt")
                    put("stream", false)
                    put("options", JSONObject().apply {
                        put("temperature", 0.2)
                        put("num_predict", 512)
                    })
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val responseJson = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    conn.disconnect()
                    val jsonObj = JSONObject(responseJson)
                    LocalModelReply(text = jsonObj.optString("response", ""))
                } else {
                    conn.disconnect()
                    LocalModelReply(error = "Ollama returned HTTP $code")
                }
            } else {
                // Llama.cpp / OpenAI Chat Completions
                val url = URL("${model.baseUrl}/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = GENERATION_READ_TIMEOUT_MS
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-llm-app", MESH_APP_ID)
                    setRequestProperty("x-llm-task", MESH_TASK)
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }

                val body = JSONObject().apply {
                    put("model", model.name)
                    put("messages", messages)
                    put("temperature", 0.2)
                    put("max_tokens", 512)
                    put("stream", false)
                    put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val responseJson = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    conn.disconnect()
                    val jsonObj = JSONObject(responseJson)
                    val choices = jsonObj.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val message = firstChoice?.optJSONObject("message")
                    val content = message?.optString("content", "").orEmpty()
                    // Some builds put the whole answer in reasoning_content when
                    // thinking is on; do not throw that answer away.
                    val reasoning = message?.optString("reasoning_content", "").orEmpty()
                    LocalModelReply(text = content.ifBlank { reasoning })
                } else {
                    conn.disconnect()
                    LocalModelReply(error = "Llama.cpp returned HTTP $code")
                }
            }
        } catch (e: Exception) {
            LocalModelReply(error = "No response from ${model.name}: ${e.message ?: "connection failed"}")
        }
    }
}
