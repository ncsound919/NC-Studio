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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.AiMixProfile
import com.example.audio.model.BufferSizePreset
import com.example.audio.model.DawSettings
import com.example.audio.model.MeterBallistics
import com.example.audio.model.UsbRoutingMode
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
import kotlin.math.roundToInt

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
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioAmber.copy(alpha = 0.2f))
                            .border(1.dp, StudioAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
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
                        Text(
                            text = "STUDIO PREFERENCES",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Engine Buffer • Hardware I/O • Metering • AI Mixing",
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
                        Text("Reset", fontSize = 11.sp)
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

            // SECTION 1: Audio Engine & Hardware Buffer
            SettingsSectionCard(
                icon = Icons.Default.Speed,
                iconColor = NeonCyan,
                title = "Audio Engine & Buffer Size"
            ) {
                Text(
                    text = "Hardware Buffer Size (Latency vs Glitch Resistance)",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Lower buffer size gives faster response for live recording; larger buffer prevents underruns with heavy DSP.",
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) NeonCyan else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Sample Rate Selector
                Text(
                    text = "Studio Sample Rate",
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioAmber.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) StudioAmber else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
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
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Latency Compensation Offset & Calibration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Input Latency Offset Calibration",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Compensates for Android audio driver recording delay when overdubbing stems.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onCalibrateLatency,
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
                        Text("Auto-Calibrate", fontSize = 11.sp)
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
                        text = "${String.format("%.1f", settings.latencyCompensationMs)} ms",
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(60.dp)
                    )
                }
            }

            // SECTION 2: USB Type-C Audio Interface Routing
            SettingsSectionCard(
                icon = Icons.Default.Usb,
                iconColor = ElectricLime,
                title = "USB-C Audio Interface Routing"
            ) {
                Text(
                    text = "Hardware Device Policy",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Select how Pulse DAW handles external USB Class-Compliant audio interfaces and DACs.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                UsbRoutingMode.values().forEach { mode ->
                    val isSelected = settings.usbRoutingMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ElectricLime.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) ElectricLime else StudioCardBorder,
                                RoundedCornerShape(8.dp)
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
                    Text("Manage Connected USB DACs & Interfaces", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // SECTION 3: Recording & Metronome Click
            SettingsSectionCard(
                icon = Icons.Default.Mic,
                iconColor = ClipRed,
                title = "Recording & Monitoring"
            ) {
                // Count-In Pre-Roll
                Text(
                    text = "Recording Count-In Pre-Roll",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Plays metronome clicks before recording starts so you are locked into the groove.",
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ClipRed.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) ClipRed else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Metronome Click Toggle & Volume
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Metronome Click Audio",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Audio click synced to project tempo during playback.",
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
                        Text("Volume", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(60.dp))
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
                }

                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Direct Input Monitoring
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Software Input Monitoring",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Feed live microphone signal back to headphones with track channel strip DSP.",
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
                Text(
                    text = "Master Metering Ballistics Standard",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Controls peak calculation and loudness display mode on faders.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                MeterBallistics.values().forEach { ballistics ->
                    val isSelected = settings.meterBallistics == ballistics
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) StudioPurple.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) StudioPurple else StudioCardBorder,
                                RoundedCornerShape(8.dp)
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Peak Hold Decay Speed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Fast Peak Hold Decay (300 ms)",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Release peak hold line quickly instead of 1.2s studio hold.",
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Haptic feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Haptic Tactile Feedback",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Gentle vibration click on fader unity detents, mute/solo switches.",
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
                Text(
                    text = "Sonic Target Profile",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Defines the tonal curve and compression goals used by the AI Mix Advisor.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                AiMixProfile.values().forEach { profile ->
                    val isSelected = settings.aiMixProfile == profile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) StudioAmber.copy(alpha = 0.15f) else StudioBackground)
                            .border(
                                1.dp,
                                if (isSelected) StudioAmber else StudioCardBorder,
                                RoundedCornerShape(8.dp)
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Auto stem classification
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Classify Stems on Import",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Identifies Vocal, Drums, Bass, Keys using spectral centroid & crest factor.",
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // On-Device Local Neural Model Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "On-Device Local Neural Model",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ElectricLime.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricLime, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = "0ms Cloud", color = ElectricLime, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "Runs 16x32x7 Quantized MLP directly on device CPU (~0.38ms). Zero internet needed.",
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
                    Text(text = "Local Neural Engine Mode", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                    .border(1.dp, if (isSel) ElectricLime else StudioCardBorder, RoundedCornerShape(6.dp))
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Small Model Auto-Detection (Ollama & Llama.cpp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Auto-Detect Local Models (Ollama & Llama)",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonCyan.copy(alpha = 0.2f))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = "Ollama / Llama", color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "Probes 10.0.2.2:11434 and local REST endpoints for small language models.",
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
                    Text(text = "Active Local Model", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioBackground)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Magnetic Grid Snapping System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Magnetic Grid Snapping",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ElectricLime.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricLime, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(text = "Precision", color = ElectricLime, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "Snaps moving & trimming clips to beat, bar, or clip boundaries.",
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
                    Text(text = "Grid Snap Resolution", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                    .border(1.dp, if (isSel) ElectricLime else StudioCardBorder, RoundedCornerShape(6.dp))
                                    .clickable { onUpdateSettings(settings.copy(gridResolution = res)) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = res.displayName,
                                    color = if (isSel) ElectricLime else TextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // Max EQ adjustment guard
                Text(
                    text = "AI Maximum EQ Boost Cap: ±${settings.aiMaxEqBoostDb.toInt()} dB",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Limits AI proposals to prevent unnatural frequency spikes or distortion.",
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioAmber.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) StudioAmber else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
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
                        Text(
                            text = "Auto-Save Interval",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically commits project changes to disk in background.",
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ElectricLime.copy(alpha = 0.2f) else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) ElectricLime else StudioCardBorder,
                                    RoundedCornerShape(8.dp)
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
                Divider(color = StudioCardBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Crash Recovery Status Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
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
                            Text("Crash Recovery Journal", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Zero-data-loss atomic state protection", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ElectricLime.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("ACTIVE", color = ElectricLime, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
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
                        Text(
                            text = "Temporary Waveform Cache",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Cached audio decoded scratch files for waveform rendering.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onClearCache,
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
                        Text("Clear Cache", fontSize = 11.sp)
                    }
                }
            }

            // SECTION 7: Real-time Audio Diagnostics & Test Suite
            SettingsSectionCard(
                icon = Icons.Default.Science,
                iconColor = StudioAmber,
                title = "DSP Diagnostics & Health"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioBackground)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mean Callback Duration:", color = TextSecondary, fontSize = 11.sp)
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
                        Text("95th Percentile Callback (P95):", color = TextSecondary, fontSize = 11.sp)
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
                        Text("Buffer Underruns (X-Runs):", color = TextSecondary, fontSize = 11.sp)
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
                        Text("Audio Thread CPU Load:", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            "${String.format("%.1f", uiState.telemetry.cpuLoadPercent)}%",
                            color = if (uiState.telemetry.cpuLoadPercent < 40f) ElectricLime else StudioAmber,
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
                    Text("Execute Full DSP Core Regression Test Suite", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // About & Version Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Pulse DAW",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Version 1.2.0 • Pro 8-Track Oboe Audio Engine",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Zero-allocation DSP Pipeline • 32-bit Float Internal Processing",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
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
                    Text(
                        text = "Reset Studio Settings?",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = "This will restore audio buffer size, sample rate, latency offset, and AI preferences to default calibration.",
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
                    Text("Reset All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, StudioCardBorder)
                ) {
                    Text("Cancel")
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
        shape = RoundedCornerShape(12.dp),
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
                        .clip(RoundedCornerShape(6.dp))
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
