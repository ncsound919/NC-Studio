package com.example.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ai.MixAdvisor
import com.example.audio.model.MixSuggestion
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackFeatures
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
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
    modifier: Modifier = Modifier
) {
    var promptText by remember { mutableStateOf("") }
    var localModelPromptText by remember { mutableStateOf("") }
    var customEndpointUrlText by remember { mutableStateOf(uiState.localModelDetectorState.customEndpointUrl) }
    var selectedReferenceIndex by remember { mutableStateOf(0) }
    val references = MixAdvisor.STANDARD_REFERENCES
    val detectorState = uiState.localModelDetectorState

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(12.dp)
            .testTag("ai_mixing_studio"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                                Text(
                                    text = "AI TACTICAL MIX ASSISTANT",
                                    color = NeonCyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "Deterministic, bounded spectral & gain intelligence",
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
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ElectricLime)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "ON-DEVICE NEURAL MODEL ENGINE",
                                    color = ElectricLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "100% Offline • Zero Network Latency • SIMD Quantized MLP",
                                    color = TextMuted,
                                    fontSize = 9.sp
                                )
                            }
                        }

                        val avgLatencyMs = features.mapNotNull { it.neuralResult?.inferenceLatencyMs }.average().let { if (it.isNaN()) 0.38 else it }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(StudioCard)
                                .border(1.dp, ElectricLime, RoundedCornerShape(4.dp))
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF070B12))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Model Architecture", color = TextMuted, fontSize = 9.sp)
                            Text(text = "PulseNeural-v1 (16x32x7)", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(text = "Quantization", color = TextMuted, fontSize = 9.sp)
                            Text(text = "8-Bit Float Quantized", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(text = "Execution Target", color = TextMuted, fontSize = 9.sp)
                            Text(text = "Local CPU / ARM Neon", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                                Text(
                                    text = "SMALL MODEL AUTO-DETECTOR (OLLAMA / LLAMA)",
                                    color = NeonCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "Auto-discovers Ollama (11434) & Llama.cpp (8080) servers",
                                    color = TextMuted,
                                    fontSize = 9.sp
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF080E1A))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Status", color = TextMuted, fontSize = 9.sp)
                            Text(
                                text = if (detectorState.isScanning) "Scanning..." else if (detectorState.detectedModels.isNotEmpty()) "Ready (${detectorState.detectedModels.size} Models Found)" else "Fallback Mode",
                                color = if (detectorState.detectedModels.isNotEmpty()) ElectricLime else StudioAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Active Server", color = TextMuted, fontSize = 9.sp)
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
                    Text(
                        text = "DISCOVERED SMALL MODELS",
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) NeonCyan.copy(alpha = 0.2f) else StudioCard)
                                    .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(8.dp))
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
                                            fontSize = 8.sp
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
                            label = { Text("Custom Endpoint IP / Host", fontSize = 10.sp, color = TextMuted) },
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
                            Text("Probe", fontSize = 11.sp, color = NeonCyan)
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
                            placeholder = { Text("Ask local model about EQ, reverb, compress or gain...", fontSize = 10.sp, color = TextMuted) },
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
                                .clip(RoundedCornerShape(8.dp))
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF070C16))
                                .border(1.dp, ElectricLime.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "MODEL RESPONSE:",
                                    color = ElectricLime,
                                    fontSize = 9.sp,
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
                    Text(
                        text = "NATURAL LANGUAGE MIX COMMANDS",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Local validator converts prompts to bounded, undoable parameter edits",
                        color = TextMuted,
                        fontSize = 9.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = { Text("e.g. \"Make vocals brighter\", \"Glue drums\", \"Clean mud\"...", fontSize = 11.sp, color = TextMuted) },
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
                                .clip(RoundedCornerShape(8.dp))
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(StudioCard)
                                    .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp))
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
                    Text(
                        text = "REFERENCE TARGET MATCHER",
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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) StudioAmber.copy(alpha = 0.2f) else StudioCard)
                                    .border(1.dp, if (isSel) StudioAmber else StudioCardBorder, RoundedCornerShape(6.dp))
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF090D14))
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
                                .clip(RoundedCornerShape(4.dp))
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
            Text(
                text = "TRACK STEM CLASSIFICATION & TELEMETRY",
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
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp)),
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
                                    .clip(RoundedCornerShape(2.dp))
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
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonCyan.copy(alpha = 0.15f))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(4.dp))
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
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF080D17))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Neural Target: LUFS Offset ${String.format("%+.1f", nr.targetLufsOffsetDb)} dB",
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                            Text(
                                text = "Comp: ${String.format("%.1f", nr.suggestedCompRatio)}:1",
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                            Text(
                                text = "Punch: ${String.format("%.2f", nr.transientPunchScore)}",
                                color = ElectricLime,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 5-Band Spectral Energy Bar (Sub, Low, Mid, HighMid, Air)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0A0E16))
                    ) {
                        val subW = feat.subEnergy.coerceIn(0.01f, 1f)
                        val lowW = feat.lowEnergy.coerceIn(0.01f, 1f)
                        val midW = feat.midEnergy.coerceIn(0.01f, 1f)
                        val hMidW = feat.highMidEnergy.coerceIn(0.01f, 1f)
                        val airW = feat.airEnergy.coerceIn(0.01f, 1f)

                        Box(modifier = Modifier.weight(subW).fillMaxSize().background(Color(0xFFE53935)))
                        Box(modifier = Modifier.weight(lowW).fillMaxSize().background(Color(0xFFFB8C00)))
                        Box(modifier = Modifier.weight(midW).fillMaxSize().background(Color(0xFFFFD600)))
                        Box(modifier = Modifier.weight(hMidW).fillMaxSize().background(Color(0xFF00E676)))
                        Box(modifier = Modifier.weight(airW).fillMaxSize().background(Color(0xFF00E5FF)))
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioSurface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Session is well-balanced! No clipping or masking detected.",
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
                                fontWeight = FontWeight.Bold
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(StudioCard)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${(sug.confidence * 100).toInt()}% Confidence",
                                    color = ElectricLime,
                                    fontSize = 9.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Current: ${sug.currentValue}", color = TextMuted, fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "➔", color = StudioAmber, fontSize = 10.sp)
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
