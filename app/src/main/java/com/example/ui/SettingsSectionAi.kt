package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.model.AiMixProfile
import com.example.audio.model.BufferSizePreset
import com.example.audio.model.DawSettings
import com.example.audio.model.MeterBallistics
import com.example.audio.model.UsbRoutingMode
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Radius
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AI mix assistant settings card (Section 5 of the settings screen).
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Section5Card(settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit) {
    SettingsSectionCard(
        icon = Icons.Default.AutoAwesome,
        iconColor = StudioAmber,
        title = "AI Mixing Studio Assistant"
    ) {
                Text(text = stringResource(R.string.ui_sonic_target_profile),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_defines_the_tonal_curve_and_compression_goals_us),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                AiMixProfile.values().forEach { profile ->
                    val isSelected = settings.aiMixProfile == profile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(if (isSelected) StudioAmber.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) StudioAmber else StudioCardBorder,
                                RoundedCornerShape(Radius.md)
                            )
                            .clickable {
                                onUpdateSettings(settings.copy(aiMixProfile = profile))
                            }
                            .padding(10.dp)
                            .testTag("ai_profile_${profile.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (isSelected) StudioAmber else TextMuted, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(StudioAmber)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = profile.displayName,
                                color = if (isSelected) StudioAmber else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = profile.description,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Auto stem classification
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_auto_classify_stems_on_import),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_identifies_vocal_drums_bass_keys_using_spectral_),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.aiStemAutoClassification,
                        onCheckedChange = { onUpdateSettings(settings.copy(aiStemAutoClassification = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioAmber,
                            checkedTrackColor = StudioAmber.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // On-Device Local Neural Model Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.ui_on_device_local_neural_model),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(ElectricLime.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricLime, RoundedCornerShape(Radius.sm))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = stringResource(R.string.ui_0ms_cloud), color = ElectricLime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(text = stringResource(R.string.ui_runs_16x32x7_quantized_mlp_directly_on_device_cp),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.enableLocalNeuralModel,
                        onCheckedChange = { onUpdateSettings(settings.copy(enableLocalNeuralModel = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ElectricLime,
                            checkedTrackColor = ElectricLime.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }

                if (settings.enableLocalNeuralModel) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.ui_local_neural_engine_mode), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "ON_DEVICE_QUANTIZED" to "8-Bit Quantized",
                            "HYBRID_ENSEMBLE" to "Hybrid Ensemble",
                            "SPECTRAL_RULES" to "Spectral Rules"
                        ).forEach { (modeKey, modeLabel) ->
                            val isSel = settings.localNeuralModelMode == modeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(if (isSel) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                    .border(1.dp, if (isSel) ElectricLime else StudioCardBorder, RoundedCornerShape(Radius.md))
                                    .clickable { onUpdateSettings(settings.copy(localNeuralModelMode = modeKey)) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = modeLabel, color = if (isSel) ElectricLime else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Small Model Auto-Detection (Ollama & Llama.cpp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.ui_auto_detect_local_models_ollama_llama),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(NeonCyan.copy(alpha = 0.2f))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(Radius.sm))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = stringResource(R.string.ui_ollama_llama), color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(text = stringResource(R.string.ui_probes_10_0_2_2_11434_and_local_rest_endpoints_f),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.autoDetectLocalModelsOnStartup,
                        onCheckedChange = { onUpdateSettings(settings.copy(autoDetectLocalModelsOnStartup = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }

                if (settings.autoDetectLocalModelsOnStartup) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.ui_active_local_model), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioBackground)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${settings.activeLocalModelName} (${settings.activeLocalModelProvider})",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = settings.customOllamaUrl,
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Magnetic Grid Snapping System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.ui_magnetic_grid_snapping),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(ElectricLime.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricLime, RoundedCornerShape(Radius.sm))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = stringResource(R.string.ui_precision), color = ElectricLime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(text = stringResource(R.string.ui_snaps_moving_trimming_clips_to_beat_bar_or_clip_),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.isMagneticSnapEnabled,
                        onCheckedChange = { onUpdateSettings(settings.copy(isMagneticSnapEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ElectricLime,
                            checkedTrackColor = ElectricLime.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }

                if (settings.isMagneticSnapEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.ui_grid_snap_resolution), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.example.audio.model.GridResolution.values().forEach { res ->
                            val isSel = settings.gridResolution == res
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(if (isSel) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                    .border(1.dp, if (isSel) ElectricLime else StudioCardBorder, RoundedCornerShape(Radius.md))
                                    .clickable { onUpdateSettings(settings.copy(gridResolution = res)) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = res.displayName,
                                    color = if (isSel) ElectricLime else TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Max EQ adjustment guard
                Text(
                    text = "AI Maximum EQ Boost Cap: ±${settings.aiMaxEqBoostDb.toInt()} dB",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_limits_ai_proposals_to_prevent_unnatural_frequen),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(3.0f, 6.0f, 12.0f).forEach { boost ->
                        val isSelected = settings.aiMaxEqBoostDb == boost
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSelected) StudioAmber.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) StudioAmber else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable {
                                    onUpdateSettings(settings.copy(aiMaxEqBoostDb = boost))
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "±${boost.toInt()} dB",
                                color = if (isSelected) StudioAmber else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
    }
}
