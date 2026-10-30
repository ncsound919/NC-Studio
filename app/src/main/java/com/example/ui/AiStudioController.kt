package com.example.ui

import com.example.audio.ai.DiscoveredModelInfo
import com.example.audio.ai.EndpointProbeResult
import com.example.audio.ai.LocalModelDetector
import com.example.audio.ai.LocalModelEndpoint
import com.example.audio.ai.LocalModelProvider
import com.example.audio.model.DawSettings
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

        uiState.update { current ->
            current.copy(
                localModelDetectorState = current.localModelDetectorState.copy(
                    isInferenceRunning = false,
                    lastInferenceResponse = reply.text ?: reply.error,
                ),
                statusMessage = if (reply.isSuccess) {
                    "Response received from ${active.name}"
                } else {
                    reply.error ?: "No response from ${active.name}"
                },
                statusKind = if (reply.isSuccess) StatusKind.SUCCESS else StatusKind.ERROR,
            )
        }
    }
}
