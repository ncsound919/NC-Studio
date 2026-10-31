package com.example.ui

import com.example.BuildConfig
import com.example.audio.ai.DiscoveredModelInfo
import com.example.audio.ai.EndpointProbeResult
import com.example.audio.ai.LocalModelDetector
import com.example.audio.ai.LocalModelEndpoint
import com.example.audio.ai.LocalModelProvider
import com.example.audio.model.DawSettings
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
    /** Disabled unless the build supplies a Tondo endpoint and device key. */
    private val tondo: TondoResearchClient = TondoResearchClient(
        TondoResearchConfig(
            baseUrl = BuildConfig.TONDO_RESEARCH_URL,
            appId = "nc-studio",
            token = BuildConfig.TONDO_RESEARCH_KEY,
        )
    ),
) {

    fun scan(
        customUrl: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = scope.launch(dispatcher) {
        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = true,
                    endpointsChecked = 0,
                ),
            )
        }

        val endpointsToTest = mutableListOf<LocalModelEndpoint>()
        endpointsToTest.addAll(LocalModelDetector.DEFAULT_ENDPOINTS)

        val custom = customUrl ?: currentSettings().customOllamaUrl
        if (custom.isNotBlank() && endpointsToTest.none { it.baseUrl == custom }) {
            endpointsToTest.add(
                LocalModelEndpoint(
                    provider = LocalModelProvider.OLLAMA,
                    baseUrl = custom,
                    displayName = "Custom Endpoint ($custom)",
                ),
            )
        }

        val probedResults = mutableListOf<EndpointProbeResult>()
        val allDetectedModels = mutableListOf<DiscoveredModelInfo>()

        for ((idx, endpoint) in endpointsToTest.withIndex()) {
            val result = LocalModelDetector.probeEndpoint(endpoint)
            probedResults.add(result)
            if (result.isOnline && result.models.isNotEmpty()) {
                allDetectedModels.addAll(result.models)
            }
            uiState.update { current ->
                current.copy(
                    localModelDetectorState = current.localModelDetectorState.copy(
                        endpointsChecked = idx + 1,
                        totalEndpointsToScan = endpointsToTest.size,
                    ),
                )
            }
        }

        val active = allDetectedModels.firstOrNull { it.name == currentSettings().activeLocalModelName }
            ?: allDetectedModels.firstOrNull()
        val markedModels = allDetectedModels.map { model ->
            model.copy(isSelected = model.name == active?.name)
        }

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isScanning = false,
                    probedEndpoints = probedResults,
                    detectedModels = markedModels,
                    activeModel = active,
                    lastScanTimestamp = System.currentTimeMillis(),
                ),
                statusMessage = if (markedModels.isEmpty()) {
                    "No local model server answered. Start Ollama or Llama.cpp, then scan again."
                } else {
                    "Scanned ${endpointsToTest.size} local model endpoints. Found ${markedModels.size} model(s)."
                },
                statusKind = if (markedModels.isEmpty()) StatusKind.INFO else StatusKind.SUCCESS,
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
