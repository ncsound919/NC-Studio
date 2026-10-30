package com.example.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.example.R
import com.example.audio.model.AiMixProfile
import com.example.audio.model.BufferSizePreset
import com.example.audio.model.DawSettings
import com.example.audio.model.MeterBallistics
import com.example.audio.model.UsbRoutingMode
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
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
import com.example.ui.theme.OnAccent

@Composable
private fun SettingsCurrentDefaultsCard(settings: DawSettings) {
    val defaults = DawSettings()
    val rows = listOf(
        Triple("Buffer", "${settings.bufferSize.frames} frames", "${defaults.bufferSize.frames} frames"),
        Triple("Sample rate", "${settings.sampleRateHz / 1000} kHz", "${defaults.sampleRateHz / 1000} kHz"),
        Triple("Latency offset", "${String.format(Locale.US, "%.1f", settings.latencyCompensationMs)} ms", "${String.format(Locale.US, "%.1f", defaults.latencyCompensationMs)} ms"),
        Triple("USB routing", settings.usbRoutingMode.displayName, defaults.usbRoutingMode.displayName),
        Triple("Metering", settings.meterBallistics.displayName, defaults.meterBallistics.displayName),
        Triple("Count-in", "${settings.countInBars} bars", "${defaults.countInBars} bars"),
        Triple("Metronome", if (settings.metronomeEnabled) "On" else "Off", if (defaults.metronomeEnabled) "On" else "Off"),
        Triple("Auto-save", "${settings.autoSaveMinutes} min", "${defaults.autoSaveMinutes} min"),
        Triple("AI profile", settings.aiMixProfile.displayName, defaults.aiMixProfile.displayName),
        Triple("Waveforms", if (settings.highResolutionWaveforms) "High-res" else "Standard", if (defaults.highResolutionWaveforms) "High-res" else "Standard"),
        Triple("Snap", if (settings.isMagneticSnapEnabled) "On" else "Off", if (defaults.isMagneticSnapEnabled) "On" else "Off"),
        Triple("Grid", settings.gridResolution.displayName, defaults.gridResolution.displayName)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_current_defaults"),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        shape = RoundedCornerShape(Radius.lg),
        border = BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = stringResource(R.string.ui_current_vs_default),
                color = NeonCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_setting), color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1.2f))
                Text(stringResource(R.string.ui_current), color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.ui_default), color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(4.dp))
            rows.forEach { (label, current, default) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                    Text(current, color = TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(default, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L * 1024L -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: DawSettings,
    uiState: EngineUiState,
    onUpdateSettings: (DawSettings) -> Unit,
    onCalibrateLatency: () -> Unit,
    onRunDspTests: () -> Unit,
    onOpenUsbDialog: () -> Unit,
    onClearCache: () -> Unit,
    onResetDefaults: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigateBack()
    }

    var showResetDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var latencyStep by remember { mutableIntStateOf(0) }
    var latencyStartStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showLatencyDialog, latencyStep, uiState.statusMessage) {
        if (!showLatencyDialog || latencyStep != 1) return@LaunchedEffect
        val message = uiState.statusMessage ?: return@LaunchedEffect
        if (message == latencyStartStatus) return@LaunchedEffect
        when {
            message.contains("Latency calibrated", ignoreCase = true) -> latencyStep = 2
            message.contains("Calibration failed", ignoreCase = true) -> latencyStep = 3
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .testTag("settings_screen")
    ) {
        // Settings Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = StudioSurface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, StudioCardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioAmber.copy(alpha = 0.2f))
                            .border(1.dp, StudioAmber.copy(alpha = 0.4f), RoundedCornerShape(Radius.md)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = StudioAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = stringResource(R.string.ui_studio_preferences),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                        Text(text = stringResource(R.string.ui_engine_buffer_hardware_i_o_metering_ai_mixing),
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.testTag("reset_defaults_button"),
                        border = BorderStroke(1.dp, StudioCardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.ui_reset),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Scrollable Settings Sections
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SettingsCurrentDefaultsCard(settings)

            // Remote session (Mode 2): an engineer can drive a take remotely.
            RemoteSessionEntryCard()

            // SECTION 1: Audio Engine & Hardware Buffer
            Section1Card(settings = settings, onUpdateSettings = onUpdateSettings, onCalibrateLatencyRequest = { latencyStep = 0; showLatencyDialog = true })

            // SECTION 2: USB Type-C Audio Interface Routing
            Section2Card(settings = settings, onUpdateSettings = onUpdateSettings, onOpenUsbDialog = onOpenUsbDialog)

            // SECTION 3: Recording & Metronome Click
            Section3Card(settings = settings, onUpdateSettings = onUpdateSettings)

            // SECTION 4: Metering & Visuals
            Section4Card(settings = settings, onUpdateSettings = onUpdateSettings)

            // SECTION 5: AI Mix Assistant Studio Preferences
            Section5Card(settings = settings, onUpdateSettings = onUpdateSettings)

            // SECTION 6: Project Persistence & Crash Recovery
            Section6Card(settings = settings, uiState = uiState, onUpdateSettings = onUpdateSettings, onClearCacheRequest = { onClearCache(); showClearCacheDialog = true })
            Section7Card(uiState = uiState, settings = settings, onRunDspTests = onRunDspTests)
            // About & Version Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                shape = RoundedCornerShape(Radius.lg),
                border = BorderStroke(1.dp, StudioCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = stringResource(R.string.ui_pulse_daw),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = stringResource(R.string.ui_version_1_2_0_pro_8_track_oboe_audio_engine),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = stringResource(R.string.ui_zero_allocation_dsp_pipeline_32_bit_float_intern),
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showLatencyDialog) {
        AlertDialog(
            onDismissRequest = { if (latencyStep != 1) showLatencyDialog = false },
            containerColor = StudioSurface,
            modifier = Modifier.testTag("latency_calibration_dialog"),
            title = {
                Text(text = stringResource(R.string.ui_input_latency_calibration),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (latencyStep) {
                        0 -> {
                            Text(text = stringResource(R.string.ui_1_connect_headphones_or_point_the_speaker_away_f),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Current offset: ${String.format(Locale.US, "%.1f", settings.latencyCompensationMs)} ms",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        1 -> {
                            Text(text = stringResource(R.string.ui_measuring_the_loopback_keep_the_room_quiet),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = NeonCyan,
                                trackColor = StudioCard
                            )
                        }
                        2 -> {
                            Text(text = stringResource(R.string.ui_measured_offset),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f", settings.latencyCompensationMs)} ms",
                                color = ElectricLime,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = stringResource(R.string.ui_applied_to_recording_alignment_you_can_fine_tune),
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        else -> {
                            Text(text = stringResource(R.string.ui_calibration_could_not_complete_check_microphone_),
                                color = ClipRed,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (latencyStep) {
                    0 -> Button(
                        onClick = {
                            latencyStartStatus = uiState.statusMessage
                            latencyStep = 1
                            onCalibrateLatency()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = OnAccent)
                    ) { Text(stringResource(R.string.ui_start_measurement), fontWeight = FontWeight.Bold) }
                    2 -> Button(
                        onClick = { showLatencyDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricLime, contentColor = OnAccent)
                    ) { Text(stringResource(R.string.ui_done), fontWeight = FontWeight.Bold) }
                    3 -> Button(
                        onClick = {
                            latencyStartStatus = uiState.statusMessage
                            latencyStep = 1
                            onCalibrateLatency()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioAmber, contentColor = OnAccent)
                    ) { Text(stringResource(R.string.ui_try_again), fontWeight = FontWeight.Bold) }
                    else -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.ui_measuring)) }
                }
            },
            dismissButton = {
                if (latencyStep != 1) {
                    OutlinedButton(onClick = { showLatencyDialog = false }) {
                        Text(stringResource(R.string.ui_close), color = TextSecondary)
                    }
                }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = StudioSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.ui_clear_waveform_cache),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = "This removes ${formatCacheSize(uiState.cacheSizeBytes)} of decoded waveform scratch files. Your project audio and recordings are not affected.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearCache()
                        showClearCacheDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = OnAccent),
                    modifier = Modifier.testTag("confirm_clear_cache_button")
                ) {
                    Text(stringResource(R.string.ui_clear_cache), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearCacheDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, StudioCardBorder)
                ) {
                    Text(stringResource(R.string.ui_cancel))
                }
            }
        )
    }

    // Confirmation Dialog for Factory Reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = StudioSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = StudioAmber,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.ui_reset_studio_settings),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(text = stringResource(R.string.ui_this_will_restore_audio_buffer_size_sample_rate_),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetDefaults()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioAmber, contentColor = OnAccent),
                    modifier = Modifier.testTag("confirm_reset_button")
                ) {
                    Text(stringResource(R.string.ui_reset_all), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, StudioCardBorder)
                ) {
                    Text(stringResource(R.string.ui_cancel))
                }
            }
        )
    }
}

/**
 * Entry point for a remote session: the artist records on this phone while an
 * engineer drives the take and listens to the monitor stream.
 */
@Composable
private fun RemoteSessionEntryCard() {
    val context = LocalContext.current
    SettingsSectionCard(
        icon = Icons.Default.Mic,
        iconColor = ElectricLime,
        title = stringResource(R.string.remote_session_title)
    ) {
        Text(
            text = stringResource(R.string.remote_session_body),
            color = TextSecondary,
            fontSize = 12.sp
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(context, com.example.session.ui.SessionActivity::class.java))
                }
            },
            border = BorderStroke(1.dp, StudioCardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricLime)
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.remote_session_open), fontSize = 12.sp)
        }
    }
}
