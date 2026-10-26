package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.MasterPreset
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.MeterGreen
import com.example.ui.theme.MeterRed
import com.example.ui.theme.MeterYellow
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.log10

/**
 * Snaps a pan value to center (0) when within a small threshold.
 * Used for pan center detent behavior.
 */
fun snapPanToCenter(pan: Float, threshold: Float = 0.05f): Float {
    return if (kotlin.math.abs(pan) < threshold) 0f else pan
}

@Composable
fun MixerView(
    session: ProjectSession,
    uiState: EngineUiState,
    onSelectTrack: (Int) -> Unit,
    onSetTrackGain: (Int, Float) -> Unit,
    onSetTrackPan: (Int, Float) -> Unit,
    onToggleMute: (Int) -> Unit,
    onToggleSolo: (Int) -> Unit,
    onToggleArm: (Int) -> Unit,
    onSetReverbSend: (Int, Float) -> Unit,
    onSetMasterGain: (Float) -> Unit,
    onApplyMasterPreset: (MasterPreset) -> Unit = {},
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Show only the selected track (single channel strip)
            val selectedTrack = session.tracks.getOrNull(uiState.selectedTrackIndex)
            if (selectedTrack != null) {
                ChannelStrip(
                    track = selectedTrack,
                    peakLevel = uiState.trackPeaks.getOrElse(uiState.selectedTrackIndex) { 0f },
                    gainReductionDb = uiState.trackGainReductions.getOrElse(uiState.selectedTrackIndex) { 0f },
                    isSelected = true,
                    onSelect = { onSelectTrack(uiState.selectedTrackIndex) },
                    onGainChange = { onSetTrackGain(uiState.selectedTrackIndex, it) },
                    onPanChange = { onSetTrackPan(uiState.selectedTrackIndex, it) },
                    onToggleMute = { onToggleMute(uiState.selectedTrackIndex) },
                    onToggleSolo = { onToggleSolo(uiState.selectedTrackIndex) },
                    onToggleArm = { onToggleArm(uiState.selectedTrackIndex) },
                    onReverbSendChange = { onSetReverbSend(uiState.selectedTrackIndex, it) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Master Bus Channel Strip
            MasterChannelStrip(
                session = session,
                masterPeakL = uiState.masterPeakL,
                masterPeakR = uiState.masterPeakR,
                momentaryLufs = uiState.momentaryLufs,
                integratedLufs = uiState.integratedLufs,
                onMasterGainChange = onSetMasterGain,
                onApplyMasterPreset = onApplyMasterPreset,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(118.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Track selector at bottom for better visibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (uiState.selectedTrackIndex > 0) onSelectTrack(uiState.selectedTrackIndex - 1) },
                enabled = uiState.selectedTrackIndex > 0,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Previous track" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = if (uiState.selectedTrackIndex > 0) NeonCyan else TextMuted
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "Trk ${uiState.selectedTrackIndex + 1} / ${session.tracks.size}",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(
                onClick = { if (uiState.selectedTrackIndex < session.tracks.size - 1) onSelectTrack(uiState.selectedTrackIndex + 1) },
                enabled = uiState.selectedTrackIndex < session.tracks.size - 1,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Next track" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (uiState.selectedTrackIndex < session.tracks.size - 1) NeonCyan else TextMuted
                )
            }
        }
    }
}

@Composable
fun ChannelStrip(
    track: TrackData,
    peakLevel: Float,
    gainReductionDb: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onGainChange: (Float) -> Unit,
    onPanChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onToggleArm: () -> Unit,
    onReverbSendChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        if (isSelected) NeonCyan else StudioCardBorder,
        label = "strip_border"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .testTag("channel_strip_${track.index}"),
        color = StudioSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Track Color Top Accent & Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(track.colorHex))
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Track Name & Category
            Text(
                text = "${track.index + 1}. ${track.name}",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = track.stemCategory.displayName,
                color = NeonCyan,
                fontSize = 9.sp,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Dedicated Channel Strip Mini Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                MiniBadge(text = "EQ", active = track.eqConfig.enabled, activeColor = NeonCyan)
                Spacer(modifier = Modifier.width(2.dp))
                MiniBadge(text = "SAT", active = track.saturationConfig.enabled, activeColor = StudioAmber)
                Spacer(modifier = Modifier.width(2.dp))
                MiniBadge(text = if (gainReductionDb > 0.5f) "-" + gainReductionDb.toInt() else "CP", active = track.compressorConfig.enabled, activeColor = if (gainReductionDb > 0.5f) StudioAmber else ElectricLime)
                Spacer(modifier = Modifier.width(2.dp))
                MiniBadge(text = "DL", active = track.delayConfig.enabled, activeColor = Color(0xFFD05CE3))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Reverb Send mini slider
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "REV ${(track.reverbSendLevel * 100).toInt()}%",
                    color = TextMuted,
                    fontSize = 8.sp
                )
                Slider(
                    value = track.reverbSendLevel,
                    onValueChange = onReverbSendChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = StudioAmber,
                        activeTrackColor = StudioAmber
                    ),
                    modifier = Modifier
                        .height(20.dp)
                        .fillMaxWidth(0.9f)
                        .semantics {
                            contentDescription = "Reverb send, ${(track.reverbSendLevel * 100).toInt()} percent"
                        }
                )
            }

            // Pan Slider
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val panText = when {
                    track.pan < -0.05f -> "L" + (-track.pan * 100).toInt()
                    track.pan > 0.05f -> "R" + (track.pan * 100).toInt()
                    else -> "C"
                }
                Text(
                    text = "PAN $panText",
                    color = TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = track.pan,
                    onValueChange = onPanChange,
                    valueRange = -1f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyan,
                        activeTrackColor = NeonCyan
                    ),
                    modifier = Modifier
                        .height(20.dp)
                        .fillMaxWidth(0.9f)
                        .semantics {
                            contentDescription = "Pan, $panText"
                        }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Mute / Solo / Arm Record Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MixerToggleBtn(
                    label = "M",
                    isActive = track.isMuted,
                    activeColor = ClipRed,
                    onClick = onToggleMute,
                    testTag = "mute_track_${track.index}",
                    contentDescription = "Mute track ${track.index + 1}"
                )
                MixerToggleBtn(
                    label = "S",
                    isActive = track.isSoloed,
                    activeColor = StudioAmber,
                    onClick = onToggleSolo,
                    testTag = "solo_track_${track.index}",
                    contentDescription = "Solo track ${track.index + 1}"
                )
                MixerToggleBtn(
                    label = "\u25cf",
                    isActive = track.isArmedForRecording,
                    activeColor = ClipRed,
                    onClick = onToggleArm,
                    testTag = "arm_track_${track.index}",
                    contentDescription = "Arm track ${track.index + 1} for recording"
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Fader & LED Peak Meter section
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vertical Fader
                VerticalFader(
                    gainDb = track.gainDb,
                    onGainChange = onGainChange,
                    modifier = Modifier
                        .width(42.dp)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(4.dp))

                // LED Peak Meter
                LedPeakMeter(
                    peakLinear = peakLevel,
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Gain dB Readout
            val gainText = if (track.gainDb <= -59.5f) "-inf" else String.format("%.1f", track.gainDb)
            Text(
                text = "$gainText dB",
                color = if (track.gainDb > 0f) StudioAmber else TextPrimary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun MasterChannelStrip(
    session: ProjectSession,
    masterPeakL: Float,
    masterPeakR: Float,
    momentaryLufs: Float,
    integratedLufs: Float,
    onMasterGainChange: (Float) -> Unit,
    onApplyMasterPreset: (MasterPreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMasterPresetDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, StudioAmber, RoundedCornerShape(8.dp))
            .testTag("master_channel_strip"),
        color = Color(0xFF161B26)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(StudioAmber)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "MASTER",
                color = StudioAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "BRICKWALL",
                color = TextMuted,
                fontSize = 8.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Pro Presets Quick Trigger Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(StudioAmber.copy(alpha = 0.2f))
                    .border(1.dp, StudioAmber, RoundedCornerShape(4.dp))
                    .clickable { showMasterPresetDialog = true }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .semantics {
                        contentDescription = "Master presets, opens preset dialog"
                    }
                    .testTag("open_master_presets_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PRESETS",
                    color = StudioAmber,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // LUFS Readouts
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF090C12))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val lufsIntStr = if (integratedLufs <= -59f) "--" else String.format("%.1f", integratedLufs)
                    Text(
                        text = "$lufsIntStr LUFS",
                        color = ElectricLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "ITU-R BS.1770",
                        color = TextMuted,
                        fontSize = 7.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Master Fader & Stereo Dual LED Peak Meters
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VerticalFader(
                    gainDb = session.masterGainDb,
                    onGainChange = onMasterGainChange,
                    modifier = Modifier
                        .width(42.dp)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(4.dp))

                LedPeakMeter(
                    peakLinear = masterPeakL,
                    modifier = Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(2.dp))

                LedPeakMeter(
                    peakLinear = masterPeakR,
                    modifier = Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val mGainText = if (session.masterGainDb <= -59.5f) "-inf" else String.format("%.1f", session.masterGainDb)
            Text(
                text = "$mGainText dB",
                color = StudioAmber,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        if (showMasterPresetDialog) {
            MasterPresetDialog(
                onSelectPreset = { preset ->
                    onApplyMasterPreset(preset)
                    showMasterPresetDialog = false
                },
                onDismiss = { showMasterPresetDialog = false }
            )
        }
    }
}

@Composable
fun VerticalFader(
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Fader scale: +6 dB (top = 1.0) down to -60 dB (bottom = 0.0)
    // 0 dB sits around fraction 0.75
    val currentFraction = remember(gainDb) {
        if (gainDb <= -60f) 0.0f
        else ((gainDb + 60f) / 66f).coerceIn(0f, 1f)
    }

    val gainText = if (gainDb <= -59.5f) "minus infinity" else "${String.format("%.1f", gainDb)} decibels"

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B0E14))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    // drag up decreases Y, which means increasing gain
                    val heightPx = size.height
                    val deltaFraction = -dragAmount / heightPx
                    val newFraction = (currentFraction + deltaFraction).coerceIn(0f, 1f)
                    val newDb = (newFraction * 66f - 60f)
                    onGainChange(newDb)
                }
            }
            .semantics {
                contentDescription = "Volume fader, $gainText. Drag up to increase, drag down to decrease."
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Track Groove
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = 12.dp)
                .background(Color(0xFF1E2638))
        )

        // 0 dB Unity tick line
        val unityPos = (60f / 66f) // fraction from bottom
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height * (1f - unityPos)
            drawLine(
                color = Color(0xFF64748B),
                start = Offset(4f, y),
                end = Offset(size.width - 4f, y),
                strokeWidth = 2f
            )
        }

        // Fader Cap (Thumb)
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val thumbHeight = 32f
            val availableTravel = size.height - thumbHeight
            val thumbY = availableTravel * (1f - currentFraction)

            // Draw fader cap with metallic grip lines
            drawRoundRect(
                color = Color(0xFFE2E8F0),
                topLeft = Offset(4f, thumbY),
                size = Size(size.width - 8f, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            // Center white line
            drawLine(
                color = Color.Black,
                start = Offset(6f, thumbY + thumbHeight / 2),
                end = Offset(size.width - 6f, thumbY + thumbHeight / 2),
                strokeWidth = 3f
            )
        }
    }
}

@Composable
fun LedPeakMeter(
    peakLinear: Float,
    modifier: Modifier = Modifier
) {
    val peakDb = if (peakLinear > 1e-5f) 20f * log10(peakLinear) else -60f
    val fraction = ((peakDb + 60f) / 66f).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF080B10))
            .border(0.5.dp, StudioCardBorder, RoundedCornerShape(3.dp))
    ) {
        val totalSegments = 24
        val segHeight = (size.height / totalSegments) - 1.5f
        val activeSegments = (fraction * totalSegments).toInt()

        for (i in 0 until totalSegments) {
            val segIndexFromBottom = i
            val y = size.height - (i + 1) * (segHeight + 1.5f)
            val isLit = segIndexFromBottom < activeSegments

            val segColor = when {
                i >= 22 -> MeterRed
                i >= 17 -> MeterYellow
                else -> MeterGreen
            }

            drawRect(
                color = if (isLit) segColor else segColor.copy(alpha = 0.12f),
                topLeft = Offset(1f, y),
                size = Size(size.width - 2f, segHeight)
            )
        }
    }
}

@Composable
fun MiniBadge(
    text: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeColor.copy(alpha = 0.2f) else Color(0xFF111622))
            .border(
                1.dp,
                if (active) activeColor else StudioCardBorder,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = if (active) activeColor else TextMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MixerToggleBtn(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String,
    contentDescription: String = "",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) activeColor else StudioCard)
            .border(
                1.dp,
                if (isActive) activeColor else StudioCardBorder,
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .semantics {
                this.contentDescription = if (isActive) "$contentDescription, enabled" else "$contentDescription, disabled"
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color.Black else TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}