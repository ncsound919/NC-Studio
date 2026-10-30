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
 * Recording & monitoring settings card (Section 3 of the settings screen).
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Section3Card(settings: DawSettings, onUpdateSettings: (DawSettings) -> Unit) {
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
}
