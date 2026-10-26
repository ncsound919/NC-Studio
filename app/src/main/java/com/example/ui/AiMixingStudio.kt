package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.ai.MixAdvisor
import com.example.audio.model.MixSuggestion
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackFeatures
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.MeterRed
import com.example.ui.theme.MeterYellow
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PanelDeep
import com.example.ui.theme.SurfaceSunken
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiMixingStudio(
    session: ProjectSession,
    uiState: EngineUiState,
    features: List<TrackFeatures>,
    suggestions: List<MixSuggestion>,
    onRefreshAnalysis: () -> Unit,
    onApplyAllSuggestions: () -> Unit,
    onToggleAbCompare: () -> Unit,
    onNaturalLanguagePrompt: (String) -> Unit,
    onScanLocalModels: ((String?) -> Unit)? = null,
    onSelectLocalModel: ((com.example.audio.ai.DiscoveredModelInfo) -> Unit)? = null,
    onSendQueryToLocalModel: ((String) -> Unit)? = null,
    onStartVoiceCommand: () -> Unit = {},
    onStopVoiceCommand: () -> Unit = {},
    onEngineerTextCommand: (String) -> Unit = onNaturalLanguagePrompt,
    modifier: Modifier = Modifier
) {
    var promptText by remember { mutableStateOf("") }
    var localModelPromptText by remember { mutableStateOf("") }
    var engineerText by remember { mutableStateOf("") }
    var microphoneDenied by remember { mutableStateOf(false) }
    var customEndpointUrlText by remember { mutableStateOf(uiState.localModelDetectorState.customEndpointUrl) }
    var selectedReferenceIndex by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val references = MixAdvisor.STANDARD_REFERENCES
    val detectorState = uiState.localModelDetectorState
    val voice = uiState.voiceEngineer
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphoneDenied = !granted
        if (granted) onStartVoiceCommand()
    }
    fun beginVoiceRequest() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            microphoneDenied = false
            onStartVoiceCommand()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .imePadding()
            .padding(12.dp)
            .testTag("ai_mixing_studio"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // VOICE ENGINEER: request a mix or a take and let the local model act.
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("voice_engineer_card"),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, ElectricLime.copy(alpha = 0.7f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = ElectricLime,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "TALK TO YOUR ENGINEER",
                                    color = ElectricLime,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "On-device speech → local model → applied, undoable mix",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        if (voice.source.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(PanelDeep)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = voice.source.uppercase(),
                                    color = NeonCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (voice.isListening) ClipRed else ElectricLime)
                                .clickable {
                                    if (voice.isListening) onStopVoiceCommand() else beginVoiceRequest()
                                }
                                .testTag("voice_engineer_mic_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (voice.isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.5.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (voice.isListening) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (voice.isListening) "Stop listening" else "Start voice command",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    voice.isListening -> "Listening… tap the button to stop."
                                    voice.isProcessing -> "Turning that into a mix move…"
                                    voice.error != null -> voice.error
                                    voice.partialTranscript.isNotBlank() -> "\"${voice.partialTranscript}\""
                                    microphoneDenied -> "Microphone permission is required. Tap to grant it."
                                    else -> "Speak a mix or record request. The engineer applies it and confirms out loud."
                                },
                                color = if (voice.error != null) ClipRed else TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            if (voice.transcript.isNotBlank() && !voice.isListening) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Heard: \"${voice.transcript}\"",
                                    color = NeonCyan,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (voice.lastAction.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = voice.lastAction,
                                    color = ElectricLime,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = engineerText,
                            onValueChange = { engineerText = it },
                            placeholder = {
                                Text(
                                    text = "Or type: set up the lead vocal and start recording",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricLime,
                                unfocusedBorderColor = StudioCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("engineer_text_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                if (engineerText.isNotBlank()) {
                                    onEngineerTextCommand(engineerText)
                                    engineerText = ""
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .background(ElectricLime)
                                .testTag("engineer_text_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send request to engineer",
                                tint = Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Set up the lead vocal",
                            "1176 on the drums",
                            "Start recording"
                        ).forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.lg))
                                    .background(StudioCard)
                                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.lg))
                                    .clickable { onEngineerTextCommand(chip) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("engineer_chip_${chip.lowercase().replace(" ", "_")}")
                            ) {
                                Text(text = chip, color = ElectricLime, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // TOP HERO: AI Mixing Assistant Status & Action Center
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonCyan.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Assistant",
                                tint = NeonCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = stringResource(R.string.ui_ai_tactical_mix_assistant),
                                    color = NeonCyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(text = stringResource(R.string.ui_deterministic_bounded_spectral_gain_intelligence),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = onRefreshAnalysis,
                            modifier = Modifier.testTag("refresh_ai_analysis_button")
                        ) {
                            if (uiState.isAnalyzingAi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = NeonCyan,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Analysis",
                                    tint = NeonCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Buttons: "Apply All" + "A/B Compare"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onApplyAllSuggestions,
                            enabled = suggestions.isNotEmpty() && !uiState.isAnalyzingAi,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("apply_all_suggestions_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Apply All (${suggestions.size})",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = onToggleAbCompare,
                            enabled = uiState.isAbComparing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.abMode == "B") NeonCyan else StudioAmber
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ab_compare_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CompareArrows,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Mode: ${uiState.abMode} (${if (uiState.abMode == "B") "AI Mix" else "Original"})",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // ON-DEVICE LOCAL NEURAL MODEL STATUS & INSPECTOR
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricLime.copy(alpha = 0.8f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(ElectricLime)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = stringResource(R.string.ui_on_device_neural_model_engine),
                                    color = ElectricLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(text = stringResource(R.string.ui_100_offline_zero_network_latency_simd_quantized_),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        val avgLatencyMs = features.mapNotNull { it.neuralResult?.inferenceLatencyMs }.average().let { if (it.isNaN()) 0.38 else it }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(StudioCard)
                                .border(1.dp, ElectricLime, RoundedCornerShape(Radius.sm))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${String.format("%.2f", avgLatencyMs)} ms",
                                color = ElectricLime,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(PanelDeep)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.ui_model_architecture), color = TextMuted, fontSize = 10.sp)
                            Text(text = stringResource(R.string.ui_pulseneural_v1_16x32x7), color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.ui_quantization), color = TextMuted, fontSize = 10.sp)
                            Text(text = stringResource(R.string.ui_8_bit_float_quantized), color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.ui_execution_target), color = TextMuted, fontSize = 10.sp)
                            Text(text = stringResource(R.string.ui_local_cpu_arm_neon), color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // SMALL MODEL AUTO-DETECTOR (OLLAMA & LLAMA.CPP)
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("small_model_detector_card"),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.8f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (detectorState.detectedModels.isNotEmpty()) ElectricLime else StudioAmber)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = stringResource(R.string.ui_small_model_auto_detector_ollama_llama),
                                    color = NeonCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(text = stringResource(R.string.ui_auto_discovers_ollama_11434_llama_cpp_8080_serve),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = { onScanLocalModels?.invoke(customEndpointUrlText) },
                            modifier = Modifier.testTag("scan_local_models_button")
                        ) {
                            if (detectorState.isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = NeonCyan,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Scan Local Endpoints",
                                    tint = NeonCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Probed Endpoints & Models Status Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(PanelDeep)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = stringResource(R.string.ui_status), color = TextMuted, fontSize = 10.sp)
                            Text(
                                text = if (detectorState.isScanning) "Scanning..." else if (detectorState.detectedModels.isNotEmpty()) "Ready (${detectorState.detectedModels.size} Models Found)" else "Fallback Mode",
                                color = if (detectorState.detectedModels.isNotEmpty()) ElectricLime else StudioAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = stringResource(R.string.ui_active_server), color = TextMuted, fontSize = 10.sp)
                            Text(
                                text = detectorState.activeModel?.let { "${it.provider.displayName} @ ${it.baseUrl.replace("http://", "")}" } ?: "http://10.0.2.2:11434",
                                color = TextPrimary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Discovered Models Selector Chips
                    Text(text = stringResource(R.string.ui_discovered_small_models),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        detectorState.detectedModels.forEach { model ->
                            val isSel = model.isSelected || model.id == detectorState.activeModel?.id || model.name == detectorState.activeModel?.name
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(if (isSel) NeonCyan.copy(alpha = 0.2f) else StudioCard)
                                    .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(Radius.md))
                                    .clickable { onSelectLocalModel?.invoke(model) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("model_chip_${model.name}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (model.provider == com.example.audio.ai.LocalModelProvider.OLLAMA) ElectricLime else StudioAmber)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = model.name,
                                            color = if (isSel) NeonCyan else TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${model.provider.displayName} • ${model.parameterSize}",
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Custom Endpoint URL Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customEndpointUrlText,
                            onValueChange = { customEndpointUrlText = it },
                            label = { Text(stringResource(R.string.ui_custom_endpoint_ip_host), fontSize = 10.sp, color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = StudioCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("custom_endpoint_url_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { onScanLocalModels?.invoke(customEndpointUrlText) },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioCard),
                            modifier = Modifier.testTag("probe_custom_url_button")
                        ) {
                            Text(stringResource(R.string.ui_probe), fontSize = 11.sp, color = NeonCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Direct Querying to Small Model
                    Text(
                        text = "QUERY ACTIVE SMALL MODEL (${detectorState.activeModel?.name ?: "llama3.2:1b"})",
                        color = ElectricLime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = localModelPromptText,
                            onValueChange = { localModelPromptText = it },
                            placeholder = { Text(stringResource(R.string.ui_ask_local_model_about_eq_reverb_compress_or_gain), fontSize = 10.sp, color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricLime,
                                unfocusedBorderColor = StudioCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("local_model_prompt_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                if (localModelPromptText.isNotBlank()) {
                                    onSendQueryToLocalModel?.invoke(localModelPromptText)
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .background(ElectricLime)
                                .testTag("send_local_model_prompt_button")
                        ) {
                            if (detectorState.isInferenceRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = Color.Black
                                )
                            }
                        }
                    }

                    detectorState.lastInferenceResponse?.let { resp ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.md))
                                .background(PanelDeep)
                                .border(1.dp, ElectricLime.copy(alpha = 0.5f), RoundedCornerShape(Radius.md))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(text = stringResource(R.string.ui_model_response),
                                    color = ElectricLime,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = resp,
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // NATURAL LANGUAGE MIX COMMAND PROMPT
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = stringResource(R.string.ui_natural_language_mix_commands),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = stringResource(R.string.ui_local_validator_converts_prompts_to_bounded_undo),
                        color = TextMuted,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = { Text(stringResource(R.string.ui_e_g_make_vocals_brighter_glue_drums_clean_mud), fontSize = 11.sp, color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = StudioCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_prompt_input")
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                if (promptText.isNotBlank()) {
                                    onNaturalLanguagePrompt(promptText)
                                    promptText = ""
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .background(NeonCyan)
                                .testTag("submit_ai_prompt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Submit",
                                tint = Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick prompt chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Brighten Vocals",
                            "Glue Drums 4:1",
                            "Clean Bass Mud",
                            "Analog Warmth",
                            "Commercial Punch"
                        ).forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.lg))
                                    .background(StudioCard)
                                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.lg))
                                    .clickable { onNaturalLanguagePrompt(chip) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(text = chip, color = NeonCyan, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // REFERENCE TARGET MATCHING
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = stringResource(R.string.ui_reference_target_matcher),
                        color = StudioAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        references.forEachIndexed { idx, ref ->
                            val isSel = idx == selectedReferenceIndex
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(if (isSel) StudioAmber.copy(alpha = 0.2f) else StudioCard)
                                    .border(1.dp, if (isSel) StudioAmber else StudioCardBorder, RoundedCornerShape(Radius.md))
                                    .clickable { selectedReferenceIndex = idx }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ref.name.split("/")[0].trim(),
                                    color = if (isSel) StudioAmber else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val activeRef = references[selectedReferenceIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(SurfaceSunken)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = activeRef.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(text = activeRef.description, color = TextMuted, fontSize = 10.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(StudioCard)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Target: ${activeRef.targetLufs} LUFS",
                                color = ElectricLime,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // SPECTRAL BANDS & STEM CLASSIFIER SUMMARY
        item {
            Text(text = stringResource(R.string.ui_track_stem_classification_telemetry),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        items(features) { feat ->
            val trk = session.tracks.find { it.index == feat.trackIndex }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md)),
                color = StudioSurface
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(Color(trk?.colorHex ?: 0xFF00E5FF))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${feat.trackIndex + 1}. ${trk?.name ?: ""}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            feat.neuralResult?.let { nr ->
                                Text(
                                    text = "Neural: ${(nr.confidence * 100).toInt()}% • ",
                                    color = ElectricLime,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(NeonCyan.copy(alpha = 0.15f))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(Radius.sm))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = feat.classifiedStem.displayName,
                                    color = NeonCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Metrics row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Peak: ${String.format("%.1f", feat.peakDb)} dBFS",
                            color = if (feat.peakDb > -2.0f) ClipRed else TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "RMS: ${String.format("%.1f", feat.rmsDb)} dBFS",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Crest: ${String.format("%.1f", feat.crestFactorDb)} dB",
                            color = StudioAmber,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    feat.neuralResult?.let { nr ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(PanelDeep)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Neural Target: LUFS Offset ${String.format("%+.1f", nr.targetLufsOffsetDb)} dB",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "Comp: ${String.format("%.1f", nr.suggestedCompRatio)}:1",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(
                                text = "Punch: ${String.format("%.2f", nr.transientPunchScore)}",
                                color = ElectricLime,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 5-Band Spectral Energy Bar (Sub, Low, Mid, HighMid, Air)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(PanelDeep)
                    ) {
                        val subW = feat.subEnergy.coerceIn(0.01f, 1f)
                        val lowW = feat.lowEnergy.coerceIn(0.01f, 1f)
                        val midW = feat.midEnergy.coerceIn(0.01f, 1f)
                        val hMidW = feat.highMidEnergy.coerceIn(0.01f, 1f)
                        val airW = feat.airEnergy.coerceIn(0.01f, 1f)

                        Box(modifier = Modifier.weight(subW).fillMaxSize().background(MeterRed))
                        Box(modifier = Modifier.weight(lowW).fillMaxSize().background(StudioAmber))
                        Box(modifier = Modifier.weight(midW).fillMaxSize().background(MeterYellow))
                        Box(modifier = Modifier.weight(hMidW).fillMaxSize().background(ElectricLime))
                        Box(modifier = Modifier.weight(airW).fillMaxSize().background(NeonCyan))
                    }
                }
            }
        }

        // VERIFIED SUGGESTIONS SECTION
        item {
            Text(
                text = "BOUNDED MIX PROPOSALS (${suggestions.size})",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (suggestions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(StudioSurface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stringResource(R.string.ui_session_is_well_balanced_no_clipping_or_masking_),
                        color = ElectricLime,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            items(suggestions) { sug ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StudioSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${sug.trackName} • ${sug.parameter}",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            "${sug.trackName} • ${sug.parameter}: " +
                                                "${sug.currentValue} -> ${sug.proposedValue}. ${sug.reason}"
                                        )
                                    )
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy suggestion",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(StudioCard)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${(sug.confidence * 100).toInt()}% Confidence",
                                    color = ElectricLime,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Current: ${sug.currentValue}", color = TextMuted, fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.ui_label), color = StudioAmber, fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Proposed: ${sug.proposedValue}", color = StudioAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = sug.reason,
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
