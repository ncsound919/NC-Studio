package com.ncstudio.daw.audio.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LocalModelProvider(val displayName: String, val defaultPort: Int) {
    OLLAMA("Ollama REST", 11434),
    LLAMA_CPP("Llama.cpp Server", 8080),
    OPEN_AI_REST("OpenAI REST API", 8000);

    companion object {
        /** Maps a persisted display name back to its provider. */
        fun fromDisplayName(name: String?): LocalModelProvider? =
            values().firstOrNull { it.displayName == name }
    }
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
    /** The Wi-Fi subnet the last scan swept, empty when no LAN scan ran. */
    val scannedSubnet: String = "",
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
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://127.0.0.1:8788", "Overlay mesh (this phone:8788)"),
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://127.0.0.1:11434", "Ollama (this phone:11434)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://127.0.0.1:8080", "Llama.cpp (this phone:8080)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://10.0.2.2:8788", "Overlay mesh (emulator host:8788)"),
        LocalModelEndpoint(LocalModelProvider.OLLAMA, "http://10.0.2.2:11434", "Ollama (emulator host:11434)"),
        LocalModelEndpoint(LocalModelProvider.LLAMA_CPP, "http://10.0.2.2:8080", "Llama.cpp (emulator host:8080)")
    )

    /** Ports the studio looks for on the local Wi-Fi network. */
    val LAN_PORTS = listOf(
        LocalModelProvider.LLAMA_CPP.defaultPort, // llama.cpp / mesh
        11434,                                    // Ollama
        8788                                      // Overlay mesh dev port
    )

    /**
     * The /24 the phone is currently on, e.g. "192.168.1". This is what makes a
     * model running on the studio desktop visible from the phone: the desktop
     * is not loopback and not 10.0.2.2, it is just another host on the Wi-Fi.
     */
    fun lanSubnetPrefix(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .filterNot { runCatching { it.isLoopback }.getOrDefault(true) }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
                ?.substringBefore('%')
                ?.split('.')
                ?.take(3)
                ?.joinToString(".")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds model servers listening on [prefix].0/24.
     *
     * A TCP connect is used as the filter, so a subnet of 254 hosts costs a few
     * hundred milliseconds instead of an HTTP timeout per address. Only hosts
     * that accept a connection are then probed over HTTP.
     */
    fun discoverLanEndpoints(
        prefix: String,
        ports: List<Int> = LAN_PORTS,
        connectTimeoutMs: Int = 220,
    ): List<LocalModelEndpoint> {
        val pool = Executors.newFixedThreadPool(48)
        val jobs = ArrayList<Callable<LocalModelEndpoint?>>(254 * ports.size)
        for (host in 1..254) {
            val address = "$prefix.$host"
            for (port in ports) {
                jobs.add(Callable {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                        }
                        val provider = when (port) {
                            11434 -> LocalModelProvider.OLLAMA
                            8080, 8788 -> LocalModelProvider.LLAMA_CPP
                            else -> LocalModelProvider.OPEN_AI_REST
                        }
                        LocalModelEndpoint(
                            provider = provider,
                            baseUrl = "http://$address:$port",
                            displayName = "Wi-Fi $address:$port",
                        )
                    } catch (_: Exception) {
                        null
                    }
                })
            }
        }
        return try {
            pool.invokeAll(jobs, 15, TimeUnit.SECONDS)
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

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

    fun probeEndpoint(
        endpoint: LocalModelEndpoint,
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 3000,
    ): EndpointProbeResult {
        val startTime = System.currentTimeMillis()
        val isOllama = endpoint.provider == LocalModelProvider.OLLAMA
        val baseUrl = endpoint.baseUrl.trimEnd('/')
        val versionPath = if (isOllama) "/api/version" else "/health"
        val version = LocalHttpTransport.get("$baseUrl$versionPath", connectTimeoutMs, readTimeoutMs)
        val latency = System.currentTimeMillis() - startTime

        if (version == null) {
            return EndpointProbeResult(
                endpoint = endpoint,
                isOnline = false,
                latencyMs = latency,
                errorMessage = "Connection failed"
            )
        }
        if (version.code !in 200..299) {
            return EndpointProbeResult(
                endpoint = endpoint,
                isOnline = false,
                latencyMs = latency,
                errorMessage = "HTTP ${version.code}"
            )
        }

        val modelsPath = if (isOllama) "/api/tags" else "/v1/models"
        val modelsResponse = LocalHttpTransport.get("$baseUrl$modelsPath", connectTimeoutMs, readTimeoutMs)
        val models = if (modelsResponse != null && modelsResponse.code in 200..299) {
            if (isOllama) parseOllamaTagsResponse(modelsResponse.body, endpoint.baseUrl)
            else parseLlamaCppModelsResponse(modelsResponse.body, endpoint.baseUrl)
        } else emptyList()

        return EndpointProbeResult(
            endpoint = endpoint,
            isOnline = true,
            latencyMs = latency,
            version = version.body.take(50),
            models = models
        )
    }

    fun generateWithLocalModel(
        model: DiscoveredModelInfo,
        prompt: String,
        systemPrompt: String = "You are a tactical audio engineer AI assistant."
    ): LocalModelReply {
        return try {
            if (model.provider == LocalModelProvider.OLLAMA) {
                val body = JSONObject().apply {
                    put("model", model.name)
                    put("prompt", "$systemPrompt\n\nUser Question: $prompt")
                    put("stream", false)
                    put("options", JSONObject().apply {
                        put("temperature", 0.2)
                        put("num_predict", 512)
                    })
                }

                val response = LocalHttpTransport.postJson(
                    "${model.baseUrl.trimEnd('/')}/api/generate",
                    body.toString(),
                    5000,
                    GENERATION_READ_TIMEOUT_MS,
                    meshHeaders(),
                ) ?: return LocalModelReply(error = "No response from ${model.name}: connection failed")

                if (response.code in 200..299) {
                    val jsonObj = JSONObject(response.body)
                    LocalModelReply(text = jsonObj.optString("response", ""))
                } else {
                    LocalModelReply(error = "Ollama returned HTTP ${response.code}")
                }
            } else {
                // Llama.cpp / OpenAI Chat Completions
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

                val response = LocalHttpTransport.postJson(
                    "${model.baseUrl.trimEnd('/')}/v1/chat/completions",
                    body.toString(),
                    5000,
                    GENERATION_READ_TIMEOUT_MS,
                    meshHeaders(),
                ) ?: return LocalModelReply(error = "No response from ${model.name}: connection failed")

                if (response.code in 200..299) {
                    val jsonObj = JSONObject(response.body)
                    val choices = jsonObj.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val message = firstChoice?.optJSONObject("message")
                    val content = message?.optString("content", "").orEmpty()
                    // Some builds put the whole answer in reasoning_content when
                    // thinking is on; do not throw that answer away.
                    val reasoning = message?.optString("reasoning_content", "").orEmpty()
                    LocalModelReply(text = content.ifBlank { reasoning })
                } else {
                    LocalModelReply(error = "Llama.cpp returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            LocalModelReply(error = "No response from ${model.name}: ${e.message ?: "connection failed"}")
        }
    }

    /** Lets the overlay mesh gateway pick this app's adapter; direct servers ignore them. */
    private fun meshHeaders(): Map<String, String> = mapOf(
        "x-llm-app" to MESH_APP_ID,
        "x-llm-task" to MESH_TASK,
    )
}
