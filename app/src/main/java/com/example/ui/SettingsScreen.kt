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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            fontFamily = FontFamily.Monospace
                        )
                        Text(text = stringResource(R.string.ui_engine_buffer_hardware_i_o_metering_ai_mixing),
                            color = TextMuted,
                            fontSize = 10.sp
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
                        Text(stringResource(R.string.ui_reset), fontSize = 11.sp)
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

            // SECTION 1: Audio Engine & Hardware Buffer
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
                        onClick = {
                            latencyStep = 0
                            showLatencyDialog = true
                        },
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

            // SECTION 2: USB Type-C Audio Interface Routing
            SettingsSectionCard(
                icon = Icons.Default.Usb,
                iconColor = ElectricLime,
                title = "USB-C Audio Interface Routing"
            ) {
                Text(text = stringResource(R.string.ui_hardware_device_policy),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_select_how_pulse_daw_handles_external_usb_class_),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                UsbRoutingMode.values().forEach { mode ->
                    val isSelected = settings.usbRoutingMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(if (isSelected) ElectricLime.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) ElectricLime else StudioCardBorder,
                                RoundedCornerShape(Radius.md)
                            )
                            .clickable {
                                onUpdateSettings(settings.copy(usbRoutingMode = mode))
                            }
                            .padding(12.dp)
                            .testTag("usb_mode_${mode.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(
                                    2.dp,
                                    if (isSelected) ElectricLime else TextMuted,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(ElectricLime)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = mode.displayName,
                                color = if (isSelected) ElectricLime else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = mode.description,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenUsbDialog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_usb_dialog_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioSurface,
                        contentColor = ElectricLime
                    ),
                    border = BorderStroke(1.dp, ElectricLime.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_manage_connected_usb_devices), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // SECTION 3: Recording & Metronome Click
            SettingsSectionCard(
                icon = Icons.Default.Mic,
                iconColor = ClipRed,
                title = "Recording & Monitoring"
            ) {
                // Count-In Pre-Roll
                Text(text = stringResource(R.string.ui_recording_count_in_pre_roll),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_plays_metronome_clicks_before_recording_starts_s),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "Off",
                        1 to "1 Bar (4 Beats)",
                        2 to "2 Bars (8 Beats)"
                    ).forEach { (bars, label) ->
                        val isSelected = settings.countInBars == bars
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSelected) ClipRed.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) ClipRed else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable {
                                    onUpdateSettings(settings.copy(countInBars = bars))
                                }
                                .padding(vertical = 10.dp)
                                .testTag("count_in_$bars"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) ClipRed else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Metronome Click Toggle & Volume
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_metronome_click_audio),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_audio_click_synced_to_project_tempo_during_playb),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.metronomeEnabled,
                        onCheckedChange = { onUpdateSettings(settings.copy(metronomeEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ClipRed,
                            checkedTrackColor = ClipRed.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("metronome_switch")
                    )
                }

                if (settings.metronomeEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.ui_volume), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = settings.metronomeVolumeDb,
                            onValueChange = { onUpdateSettings(settings.copy(metronomeVolumeDb = it)) },
                            valueRange = -36f..0f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = ClipRed,
                                activeTrackColor = ClipRed,
                                inactiveTrackColor = StudioBackground
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${settings.metronomeVolumeDb.roundToInt()} dB",
                            color = ClipRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(55.dp)
                        )
                    }
                    Text(
                        text = "Default ${DawSettings().metronomeVolumeDb.roundToInt()} dB",
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.testTag("metronome_default_hint")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Direct Input Monitoring
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_software_input_monitoring),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_feed_live_microphone_signal_back_to_headphones_w),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.enableInputMonitoring,
                        onCheckedChange = { onUpdateSettings(settings.copy(enableInputMonitoring = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        ),
                        modifier = Modifier.testTag("input_monitoring_switch")
                    )
                }
            }

            // SECTION 4: Metering & Visuals
            SettingsSectionCard(
                icon = Icons.Default.Tune,
                iconColor = StudioPurple,
                title = "Metering & Visuals"
            ) {
                Text(text = stringResource(R.string.ui_master_metering_ballistics_standard),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = stringResource(R.string.ui_controls_peak_calculation_and_loudness_display_m),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                MeterBallistics.values().forEach { ballistics ->
                    val isSelected = settings.meterBallistics == ballistics
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(if (isSelected) StudioPurple.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) StudioPurple else StudioCardBorder,
                                RoundedCornerShape(Radius.md)
                            )
                            .clickable {
                                onUpdateSettings(settings.copy(meterBallistics = ballistics))
                            }
                            .padding(10.dp)
                            .testTag("meter_${ballistics.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (isSelected) StudioPurple else TextMuted, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(StudioPurple)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = ballistics.displayName,
                                color = if (isSelected) StudioPurple else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = ballistics.description,
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

                // Peak Hold Decay Speed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_fast_peak_hold_decay_300_ms),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_release_peak_hold_line_quickly_instead_of_1_2s_s),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.peakHoldDecayFast,
                        onCheckedChange = { onUpdateSettings(settings.copy(peakHoldDecayFast = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioPurple,
                            checkedTrackColor = StudioPurple.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Haptic feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_haptic_tactile_feedback),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_gentle_vibration_click_on_fader_unity_detents_mu),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.enableHapticFeedback,
                        onCheckedChange = { onUpdateSettings(settings.copy(enableHapticFeedback = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.3f),
                            uncheckedTrackColor = StudioBackground
                        )
                    )
                }
            }

            // SECTION 5: AI Mix Assistant Studio Preferences
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

            // SECTION 6: Project Persistence & Crash Recovery
            SettingsSectionCard(
                icon = Icons.Default.Save,
                iconColor = ElectricLime,
                title = "Storage & Crash Recovery"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_auto_save_interval),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_automatically_commits_project_changes_to_disk_in),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1 to "1 Min", 2 to "2 Min", 5 to "5 Min", 0 to "Manual").forEach { (min, label) ->
                        val isSelected = settings.autoSaveMinutes == min
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSelected) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) ElectricLime else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable {
                                    onUpdateSettings(settings.copy(autoSaveMinutes = min))
                                }
                                .padding(vertical = 8.dp)
                                .testTag("autosave_$min"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) ElectricLime else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Crash Recovery Status Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(StudioBackground)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ElectricLime,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(stringResource(R.string.ui_crash_recovery_journal), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.ui_zero_data_loss_atomic_state_protection), color = TextMuted, fontSize = 10.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(ElectricLime.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(stringResource(R.string.ui_active), color = ElectricLime, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scratch Cache Cleanup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ui_temporary_waveform_cache),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = stringResource(R.string.ui_cached_audio_decoded_scratch_files_for_waveform_),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Currently ${formatCacheSize(uiState.cacheSizeBytes)}",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("cache_size_label")
                        )
                    }
                    OutlinedButton(
                        onClick = { showClearCacheDialog = true },
                        border = BorderStroke(1.dp, StudioCardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        modifier = Modifier.testTag("clear_cache_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.ui_clear_cache), fontSize = 11.sp)
                    }
                }
            }

            // SECTION 7: Real-time Audio Diagnostics & Test Suite
            if (!uiState.presentationMode) {
            SettingsSectionCard(
                icon = Icons.Default.Science,
                iconColor = StudioAmber,
                title = "DSP diagnostics & health"
            ) {
                SessionHealthCard(uiState = uiState, settings = settings)

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(StudioBackground)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_mean_callback_duration), color = TextSecondary, fontSize = 11.sp)
                        Text(
                            "${String.format("%.2f", uiState.telemetry.callbackMeanMs)} ms",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_95th_percentile_callback_p95), color = TextSecondary, fontSize = 11.sp)
                        Text(
                            "${String.format("%.2f", uiState.telemetry.callbackP95Ms)} ms",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_buffer_underruns_x_runs), color = TextSecondary, fontSize = 11.sp)
                        Text(
                            "${uiState.telemetry.bufferUnderruns}",
                            color = if (uiState.telemetry.bufferUnderruns == 0) ElectricLime else ClipRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_audio_thread_cpu_load), color = TextSecondary, fontSize = 11.sp)
                        Text(
                            "${String.format("%.1f", uiState.telemetry.cpuLoadPercent)}%",
                            color = if (uiState.telemetry.cpuLoadPercent < 40f) ElectricLime else StudioAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_ui_frame_mean_p95), color = TextSecondary, fontSize = 11.sp)
                        Text(
                            if (uiState.uiFrameP95Ms > 0f) {
                                "${String.format("%.1f", uiState.uiFrameMeanMs)} / ${String.format("%.1f", uiState.uiFrameP95Ms)} ms"
                            } else {
                                "measuring…"
                            },
                            color = when {
                                uiState.uiFrameP95Ms >= 34f -> ClipRed
                                uiState.uiFrameP95Ms >= 20f -> StudioAmber
                                uiState.uiFrameP95Ms > 0f -> ElectricLime
                                else -> TextMuted
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRunDspTests,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_dsp_tests_settings_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCard,
                        contentColor = StudioAmber
                    ),
                    border = BorderStroke(1.dp, StudioAmber.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_run_dsp_regression_suite), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            }

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
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                    ) { Text(stringResource(R.string.ui_start_measurement), fontWeight = FontWeight.Bold) }
                    2 -> Button(
                        onClick = { showLatencyDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricLime, contentColor = Color.Black)
                    ) { Text(stringResource(R.string.ui_done), fontWeight = FontWeight.Bold) }
                    3 -> Button(
                        onClick = {
                            latencyStartStatus = uiState.statusMessage
                            latencyStep = 1
                            onCalibrateLatency()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioAmber, contentColor = Color.Black)
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
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
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
                    colors = ButtonDefaults.buttonColors(containerColor = StudioAmber, contentColor = Color.Black),
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

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        shape = RoundedCornerShape(Radius.lg),
        border = BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(iconColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            content()
        }
    }
}

@Composable
private fun SessionHealthCard(uiState: EngineUiState, settings: DawSettings) {
    val health = buildSessionHealth(
        telemetry = uiState.telemetry,
        uiFrameMeanMs = uiState.uiFrameMeanMs,
        uiFrameP95Ms = uiState.uiFrameP95Ms,
        settings = settings
    )
    val accent = when (health.level) {
        HealthLevel.HEALTHY -> ElectricLime
        HealthLevel.FAIR -> StudioAmber
        HealthLevel.AT_RISK -> ClipRed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(Radius.md))
            .padding(12.dp)
            .testTag("session_health_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (health.level == HealthLevel.HEALTHY) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = health.level.label,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag("session_health_level")
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(stringResource(R.string.ui_live), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(health.headline, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        health.details.forEach { detail ->
            Text("• $detail", color = TextSecondary, fontSize = 11.sp)
        }
    }
}
