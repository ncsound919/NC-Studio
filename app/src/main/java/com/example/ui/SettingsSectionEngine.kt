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
 * Engine & buffer size settings card (Section 1 of the settings screen).
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Section1Card(settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit, onCalibrateLatencyRequest: () -> Unit) {
    SettingsSectionCard(
        icon = Icons.Default.Speed,
        iconColor = NeonCyan,
        title = "Audio Engine & Buffer Size"
    ) {
                Text(text = stringResource(R.string.ui_hardware_buffer_size_latency_vs_glitch_resistanc),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_lower_buffer_size_gives_faster_response_for_live),
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Buffer Size Presets
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BufferSizePreset.values().forEach { preset ->
                        val isSelected = settings.bufferSize == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) NeonCyan else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable {
                                    onUpdateSettings(settings.copy(bufferSize = preset))
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("buffer_${preset.frames}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${preset.frames} Frames",
                                    color = if (isSelected) NeonCyan else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${String.format("%.1f", preset.latencyMs)} ms",
                                    color = if (isSelected) ElectricLime else TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Sample Rate Selector
                Text(text = stringResource(R.string.ui_studio_sample_rate),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(44100, 48000, 96000).forEach { rate ->
                        val isSelected = settings.sampleRateHz == rate
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
                                    onUpdateSettings(settings.copy(sampleRateHz = rate))
                                }
                                .padding(vertical = 8.dp)
                                .testTag("sample_rate_$rate"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${rate / 1000}.${(rate % 1000) / 100} kHz",
                                    color = if (isSelected) StudioAmber else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (rate == 48000) "Pro Studio" else if (rate == 44100) "CD Audio" else "Hi-Res",
                                    color = if (isSelected) StudioAmber else TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Latency Compensation Offset & Calibration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_input_latency_offset_calibration),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_compensates_for_android_audio_driver_recording_d),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onCalibrateLatencyRequest,
                        modifier = Modifier.testTag("calibrate_latency_button"),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.ui_auto_calibrate), fontSize = 11.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = settings.latencyCompensationMs,
                        onValueChange = { onUpdateSettings(settings.copy(latencyCompensationMs = it)) },
                        valueRange = 0f..80f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("latency_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan,
                            inactiveTrackColor = StudioBackground
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${String.format(Locale.US, "%.1f", settings.latencyCompensationMs)} ms",
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(60.dp)
                    )
                }
                Text(
                    text = "Default ${String.format(Locale.US, "%.1f", DawSettings().latencyCompensationMs)} ms",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("latency_default_hint")
                )
    }
}
