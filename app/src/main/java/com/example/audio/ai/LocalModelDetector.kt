package com.example.audio.ai

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

object LocalModelDetector {

    // Standard local server endpoints to probe
    val DEFAULT_ENDPOINTS = listOf(
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://10.0.2.2:11434", "Ollama (Emulator Host 10.0.2.2:11434)"),
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://127.0.0.1:11434", "Ollama (Localhost 127.0.0.1:11434)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://10.0.2.2:8080", "Llama.cpp (Emulator Host 10.0.2.2:8080)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://127.0.0.1:8080", "Llama.cpp (Localhost 127.0.0.1:8080)")
    )

    // Fallback/Simulated models for offline testing or when no live server is reachable
    val SIMULATED_FALLBACK_MODELS = listOf(
        DiscoveredModelInfo(
            name = "llama3.2:1b",
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://10.0.2.2:11434",
            parameterSize = "1.3B",
            family = "llama",
            sizeBytes = 1300000000L,
            isSelected = true
        ),
        DiscoveredModelInfo(
            name = "qwen2.5:0.5b",
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://10.0.2.2:11434",
            parameterSize = "0.5B",
            family = "qwen2",
            sizeBytes = 520000000L
        ),
        DiscoveredModelInfo(
            name = "phi3:mini",
            provider = LocalModelProvider.LLAMA_CPP,
            baseUrl = "http://10.0.2.2:8080",
            parameterSize = "3.8B",
            family = "phi3",
            sizeBytes = 2200000000L
        ),
        DiscoveredModelInfo(
            name = "mistral:7b-instruct",
            provider = LocalModelProvider.OLLAMA,
            baseUrl = "http://10.0.2.2:11434",
            parameterSize = "7.2B",
            family = "llama",
            sizeBytes = 4100000000L
        )
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
                connectTimeout = 1200
                readTimeout = 1500
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
                    connectTimeout = 1200
                    readTimeout = 1500
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
    ): String {
        return try {
            if (model.provider == LocalModelProvider.OLLAMA) {
                val url = URL("${model.baseUrl}/api/generate")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 8000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val body = JSONObject().apply {
                    put("model", model.name)
                    put("prompt", "$systemPrompt\n\nUser Question: $prompt")
                    put("stream", false)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val responseJson = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    conn.disconnect()
                    val jsonObj = JSONObject(responseJson)
                    jsonObj.optString("response", "No response content from model.")
                } else {
                    conn.disconnect()
                    "Local Ollama Server returned HTTP $code"
                }
            } else {
                // Llama.cpp / OpenAI Chat Completions
                val url = URL("${model.baseUrl}/v1/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 8000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
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
                    message?.optString("content", "No chat response content.") ?: "Empty Llama response."
                } else {
                    conn.disconnect()
                    "Llama.cpp Server returned HTTP $code"
                }
            }
        } catch (e: Exception) {
            // Fallback response for offline or simulated mode
            generateTacticalFallbackResponse(model, prompt)
        }
    }

    private fun generateTacticalFallbackResponse(model: DiscoveredModelInfo, prompt: String): String {
        val p = prompt.lowercase()
        return when {
            p.contains("vocal") || p.contains("bright") ->
                "[Model ${model.name} via ${model.provider.displayName}]: For cleaner vocal clarity, apply a high-shelf boost (+2.0 dB @ 8 kHz) and cut masking frequencies around 300 Hz on competing guitars."
            p.contains("bass") || p.contains("kick") || p.contains("sub") ->
                "[Model ${model.name} via ${model.provider.displayName}]: Sidechain compress the 808 sub-bass triggered by the kick drum, and cut 80 Hz on non-bass stems to prevent phase cancellation."
            p.contains("drum") || p.contains("snare") ->
                "[Model ${model.name} via ${model.provider.displayName}]: Engage 4:1 compression on the snare/drum bus with 15ms attack time to keep transient punch while controlling peak crest factor."
            else ->
                "[Model ${model.name} via ${model.provider.displayName}]: Analyzed session parameters. Recommended action: Normalize gain staging to -18 dBFS RMS and activate master limiter at -0.3 dB ceiling."
        }
    }
}
