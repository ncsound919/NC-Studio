package com.ncstudio.daw.ui

import com.ncstudio.daw.BuildConfig
import com.ncstudio.daw.audio.ai.DiscoveredModelInfo
import com.ncstudio.daw.audio.ai.EndpointProbeResult
import com.ncstudio.daw.audio.ai.LocalModelDetector
import com.ncstudio.daw.audio.ai.LocalModelEndpoint
import com.ncstudio.daw.audio.ai.LocalModelProvider
import com.ncstudio.daw.audio.model.DawSettings
import com.overlay.tondo.TondoResearchClient
import com.overlay.tondo.TondoResearchConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns local-model discovery and inference state.
 *
 * The studio is deliberately honest: a scan that finds nothing leaves the
 * active model empty and tells the user so. There is no simulated fallback,
 * because those models were never there to answer.
 */
class AiStudioController(
    private val scope: CoroutineScope,
    private val currentSettings: () -> DawSettings,
    private val updateSettings: ((DawSettings) -> DawSettings) -> Unit,
    private val uiState: MutableStateFlow<EngineUiState>,
    /** Injected so the two-phase scan can be tested without opening sockets. */
    private val lanSubnetPrefix: () -> String? = LocalModelDetector::lanSubnetPrefix,
    private val discoverLanEndpoints: (String) -> List<LocalModelEndpoint> =
        { prefix -> LocalModelDetector.discoverLanEndpoints(prefix) },
    private val probeEndpoint: (LocalModelEndpoint, Int, Int) -> EndpointProbeResult =
        LocalModelDetector::probeEndpoint,
    /** Disabled unless the build supplies a Tondo endpoint and device key. */
    private val tondo: TondoResearchClient = TondoResearchClient(
        TondoResearchConfig(
            baseUrl = BuildConfig.TONDO_RESEARCH_URL,
            appId = "nc-studio",
            token = BuildConfig.TONDO_RESEARCH_KEY,
        )
    ),
) {

    /**
     * Puts the last model the user picked back on screen before a scan runs.
     *
     * The selected model and endpoint are persisted with the rest of the
     * studio settings, so "my LLM" survives an app restart instead of looking
     * disconnected until someone finds the scan button again.
     */
    fun restoreFromSettings() {
        val settings = currentSettings()
        val name = settings.activeLocalModelName.trim()
        val url = settings.customOllamaUrl.trim()
        if (url.isNotEmpty()) {
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        customEndpointUrl = url,
                    ),
                )
            }
        }
        if (name.isEmpty() || url.isEmpty()) return
        val provider = LocalModelProvider.fromDisplayName(settings.activeLocalModelProvider)
            ?: LocalModelProvider.OLLAMA
        val restored = DiscoveredModelInfo(
            name = name,
            provider = provider,
            baseUrl = url,
            isSelected = true,
        )
        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    activeModel = restored,
                ),
            )
        }
    }

    /**
     * Probes the local server endpoints.
     *
     * [deep] adds the Wi-Fi sweep. It is off for the automatic scan at launch:
     * 254 hosts times three ports is a lot of sockets to spend every time the
     * app opens, so the subnet is only searched when the user asks for it.
     */
    fun scan(
        customUrl: String? = null,
        deep: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = scope.launch(dispatcher) {
        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = true,
                    endpointsChecked = 0,
                    totalEndpointsToScan = 0,
                ),
            )
        }

        // Phase 1: loopback / emulator host + the endpoint the user saved.
        val localEndpoints = mutableListOf<LocalModelEndpoint>()
        localEndpoints.addAll(LocalModelDetector.DEFAULT_ENDPOINTS)
        val custom = (customUrl ?: currentSettings().customOllamaUrl).trim()
        if (custom.isNotBlank()) {
            val normalized = (if (custom.startsWith("http")) custom else "http://$custom").trimEnd('/')
            if (localEndpoints.none { it.baseUrl == normalized }) {
                localEndpoints.add(
                    LocalModelEndpoint(
                        provider = if (normalized.contains("11434")) {
                            LocalModelProvider.OLLAMA
                        } else {
                            LocalModelProvider.LLAMA_CPP
                        },
                        baseUrl = normalized,
                        displayName = "Saved endpoint ($normalized)",
                    ),
                )
            }
        }

        val probedResults = mutableListOf<EndpointProbeResult>()
        val allDetectedModels = mutableListOf<DiscoveredModelInfo>()

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    totalEndpointsToScan = localEndpoints.size,
                ),
            )
        }

        for ((idx, endpoint) in localEndpoints.withIndex()) {
            val result = probeEndpoint(endpoint, 900, 1200)
            probedResults.add(result)
            if (result.isOnline && result.models.isNotEmpty()) {
                allDetectedModels.addAll(result.models)
            }
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        endpointsChecked = idx + 1,
                        totalEndpointsToScan = localEndpoints.size,
                    ),
                )
            }
        }

        // Phase 2: the rest of the phone's Wi-Fi subnet. This is the case that
        // matters on a real device: the model runs on the studio desktop, not
        // on loopback and not on the emulator host alias. Only an explicit scan
        // asks for this sweep.
        val lanPrefix = lanSubnetPrefix()
        var scannedSubnet = ""
        if (deep && lanPrefix != null && allDetectedModels.isEmpty()) {
            scannedSubnet = "$lanPrefix.1-254"
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        endpointsChecked = 0,
                        totalEndpointsToScan = 0,
                        scannedSubnet = scannedSubnet,
                    ),
                    statusMessage = "No server on this phone. Scanning Wi-Fi ($scannedSubnet)…",
                    statusKind = StatusKind.INFO,
                )
            }
            val lanEndpoints = discoverLanEndpoints(lanPrefix)
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        totalEndpointsToScan = lanEndpoints.size,
                    ),
                )
            }
            for ((idx, endpoint) in lanEndpoints.withIndex()) {
                val result = probeEndpoint(endpoint, 900, 1500)
                probedResults.add(result)
                if (result.isOnline && result.models.isNotEmpty()) {
                    allDetectedModels.addAll(result.models)
                }
                uiState.update { current ->
                    current.copy(
                        localModelDetectorState = current.localModelDetectorState.copy(
                            endpointsChecked = idx + 1,
                            totalEndpointsToScan = lanEndpoints.size,
                        ),
                    )
                }
            }
        }

        val previousActive = uiState.value.localModelDetectorState.activeModel
        val active = allDetectedModels.firstOrNull { it.name == currentSettings().activeLocalModelName }
            ?: allDetectedModels.firstOrNull()
            ?: previousActive
        val markedModels = allDetectedModels.map { model ->
            model.copy(isSelected = model.name == active?.name)
        }

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = false,
                    // Keep an endpoint the user already had, even if the scan
                    // found nothing: a sleeping server is not a lost setting.
                    probedEndpoints = probedResults,
                    detectedModels = markedModels.ifEmpty { current.localModelDetectorState.detectedModels },
                    activeModel = active,
                    scannedSubnet = scannedSubnet,
                    lastScanTimestamp = System.currentTimeMillis(),
                ),
                statusMessage = when {
                    markedModels.isNotEmpty() ->
                        "Found ${markedModels.size} model(s) on ${markedModels.first().baseUrl}."
                    previousActive != null ->
                        "No server answered. ${previousActive.name} is still selected at ${previousActive.baseUrl}."
                    !deep && lanPrefix != null ->
                        "No server on this phone. Tap Scan to look for a model on your Wi-Fi ($lanPrefix.0/24)."
                    scannedSubnet.isNotEmpty() ->
                        "No local model answered on this phone or on Wi-Fi ($scannedSubnet). Start Ollama or llama.cpp on the same network, then scan again."
                    else ->
                        "No local model server answered. Start Ollama or llama.cpp, then scan again."
                },
                statusKind = if (markedModels.isNotEmpty()) StatusKind.SUCCESS else StatusKind.INFO,
            )
        }
    }

    fun select(model: DiscoveredModelInfo) {
        val updatedList = uiState.value.localModelDetectorState.detectedModels.map { candidate ->
            candidate.copy(isSelected = candidate.id == model.id || candidate.name == model.name)
        }
        val selected = updatedList.find { it.isSelected } ?: model.copy(isSelected = true)

        updateSettings { settings ->
            settings.copy(
                activeLocalModelName = selected.name,
                activeLocalModelProvider = selected.provider.displayName,
                customOllamaUrl = selected.baseUrl,
            )
        }

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    detectedModels = updatedList,
                    activeModel = selected,
                ),
                statusMessage = "Selected local model: ${selected.name} " +
                    "(${selected.provider.displayName} @ ${selected.baseUrl})",
            )
        }
    }

    fun setCustomEndpointUrl(url: String) {
        updateSettings { it.copy(customOllamaUrl = url) }
        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(customEndpointUrl = url),
            )
        }
        scan(url)
    }

    fun send(
        prompt: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = scope.launch(dispatcher) {
        val active = uiState.value.localModelDetectorState.activeModel
        if (active == null) {
            if (!tondo.isConfigured) {
                uiState.update { current ->
                    current.copy(
                        localModelDetectorState = current.localModelDetectorState.copy(
                            lastInferenceResponse = null,
                        ),
                        statusMessage = "No local model is connected. Scan for a server before sending a query.",
                        statusKind = StatusKind.ERROR,
                    )
                }
                return@launch
            }

            // No local model is not an error the user should have to solve
            // before asking: it is the clearest reason to use the bounded
            // research lane. The question goes to Tondo, never the session.
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        isInferenceRunning = true,
                        lastInferenceResponse = null,
                    ),
                    statusMessage = "No local model connected. Asking Tondo Research...",
                    statusKind = StatusKind.INFO,
                )
            }
            val assisted = runCatching {
                tondo.assist(
                    operation = "audio mix research",
                    question = prompt,
                    symptom = "no local model is connected",
                    context = "no local model active; provider: none",
                    dataClass = "internal",
                )
            }.getOrNull()
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        isInferenceRunning = false,
                        lastInferenceResponse = assisted?.toPlainText(),
                    ),
                    statusMessage = if (assisted != null) {
                        "Tondo research assist (no local model)"
                    } else {
                        "No local model is connected and Tondo did not answer."
                    },
                    statusKind = if (assisted != null) StatusKind.SUCCESS else StatusKind.ERROR,
                )
            }
            return@launch
        }

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isInferenceRunning = true,
                    lastInferenceResponse = null,
                ),
                statusMessage = "Sending query to ${active.name}...",
            )
        }

        val reply = LocalModelDetector.generateWithLocalModel(
            model = active,
            prompt = prompt,
            systemPrompt = "You are a professional audio mixing engineer and DAW tactical advisor.",
        )

        // A local model can be absent, overloaded, pointed at a dead port, or
        // return an empty completion. Tondo is the bounded second opinion, not a
        // replacement: it receives the question and the failure, never the
        // session or the audio.
        val localAnswered = reply.isSuccess && !reply.text.isNullOrBlank()
        val assisted = if (!localAnswered && tondo.isConfigured) {
            runCatching {
                tondo.assist(
                    operation = "audio mix research",
                    question = prompt,
                    symptom = reply.error ?: "the local model returned no text",
                    context = "local model: ${active.name}; provider: ${active.provider.displayName}",
                    dataClass = "internal",
                )
            }.getOrNull()
        } else {
            null
        }

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isInferenceRunning = false,
                    lastInferenceResponse = assisted?.toPlainText()
                        ?: reply.text?.takeIf { it.isNotBlank() }
                        ?: reply.error,
                ),
                statusMessage = when {
                    assisted != null -> "Tondo research assist (${active.name} did not answer)"
                    localAnswered -> "Response received from ${active.name}"
                    else -> reply.error ?: "No response from ${active.name}"
                },
                statusKind = if (assisted != null || localAnswered) StatusKind.SUCCESS else StatusKind.ERROR,
            )
        }
    }
}
