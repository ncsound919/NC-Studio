package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.model.ProjectSession
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.Motion
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.motionSpec
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.OnAccent
import com.example.ui.theme.OverlayWhite

@Composable
fun TransportBar(
    session: ProjectSession,
    uiState: EngineUiState,
    canUndo: Boolean,
    canRedo: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onToggleRecord: () -> Unit,
    onToggleLoop: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCalibrateLatency: () -> Unit,
    onSetPunchIn: () -> Unit = {},
    onSetPunchOut: () -> Unit = {},
    onClearPunch: () -> Unit = {},
    onToggleAb: () -> Unit = {},
    onLoadReference: () -> Unit = {},
    onMatchToReference: () -> Unit = {},
    hasReferenceTrack: Boolean = false,
    onDismissStatus: () -> Unit = {},
    onStatusAction: () -> Unit = {},
    showDiagnostics: Boolean = true,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transport_bar"),
        color = StudioSurface,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.md))
                            .background(com.example.ui.theme.PanelInset)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.timeFormatted,
                            color = NeonCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.testTag("timecode_display")
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioCard)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${session.tempoBpm.toInt()} BPM",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("transport_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.transport_stop),
                            tint = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = {
                            haptics.studioTick(hapticsEnabled)
                            onTogglePlay()
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isPlaying) ElectricLime else NeonCyan)
                            .semantics {
                                stateDescription = if (uiState.isPlaying) "Playing" else "Paused"
                            }
                            .testTag("transport_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (uiState.isPlaying) R.string.transport_pause else R.string.transport_play
                            ),
                            tint = OnAccent
                        )
                    }

                    val recBg by animateColorAsState(
                        if (uiState.isRecording) ClipRed else StudioCard,
                        animationSpec = motionSpec(Motion.MEDIUM),
                        label = "rec_bg"
                    )
                    IconButton(
                        onClick = {
                            haptics.studioConfirm(hapticsEnabled)
                            onToggleRecord()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(recBg)
                            .semantics {
                                stateDescription = if (uiState.isRecording) "Recording" else "Not recording"
                            }
                            .testTag("transport_record_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = stringResource(
                                if (uiState.isRecording) R.string.transport_stop_recording else R.string.transport_record
                            ),
                            tint = if (uiState.isRecording) OverlayWhite else ClipRed
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("undo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.transport_undo),
                        tint = if (canUndo) TextPrimary else TextMuted
                    )
                }

                IconButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("redo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = stringResource(R.string.transport_redo),
                        tint = if (canRedo) TextPrimary else TextMuted
                    )
                }

                val loopColor by animateColorAsState(
                    if (session.loopEnabled) NeonCyan else TextMuted,
                    animationSpec = motionSpec(Motion.SHORT),
                    label = "loop_color"
                )
                IconButton(
                    onClick = onToggleLoop,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            stateDescription = if (session.loopEnabled) "Loop on" else "Loop off"
                        }
                        .testTag("transport_loop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Loop,
                        contentDescription = stringResource(R.string.transport_toggle_loop),
                        tint = loopColor
                    )
                }

                if (uiState.isRecording) {
                    Text(
                        text = stringResource(R.string.transport_recording_badge),
                        color = ClipRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .testTag("recording_badge")
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransportToolButton(
                        label = stringResource(R.string.transport_punch_in),
                        selected = uiState.punchInFrame != null,
                        onClick = onSetPunchIn,
                        testTag = "punch_in_button"
                    )
                    TransportToolButton(
                        label = stringResource(R.string.transport_punch_out),
                        selected = uiState.punchOutFrame != null,
                        onClick = onSetPunchOut,
                        testTag = "punch_out_button"
                    )
                    TransportToolButton(
                        label = stringResource(R.string.transport_clear),
                        enabled = uiState.punchInFrame != null || uiState.punchOutFrame != null,
                        onClick = onClearPunch,
                        testTag = "clear_punch_button"
                    )
                    TransportToolButton(
                        label = stringResource(R.string.transport_ab),
                        selected = uiState.isReferenceAuditioning,
                        enabled = hasReferenceTrack,
                        onClick = onToggleAb,
                        testTag = "ab_compare_button"
                    )
                    TransportToolButton(
                        label = stringResource(R.string.transport_load_reference),
                        onClick = onLoadReference,
                        testTag = "load_reference_button"
                    )
                    TransportToolButton(
                        label = stringResource(R.string.transport_match_reference),
                        enabled = hasReferenceTrack,
                        onClick = onMatchToReference,
                        testTag = "match_reference_button"
                    )

                    // Level-matched A/B readout: what the reference measures, what
                    // the mix measures, and which side you are hearing right now.
                    if (hasReferenceTrack) {
                        val refLufs = uiState.referenceProfile?.integratedLufs
                        val mixLufs = uiState.mixProfile?.integratedLufs
                        val readout = buildString {
                            append(if (uiState.isReferenceAuditioning) "B: ref" else "A: mix")
                            mixLufs?.let { append(String.format(java.util.Locale.US, " %.1f", it)) }
                            refLufs?.let { append(String.format(java.util.Locale.US, " / ref %.1f LUFS", it)) }
                        }
                        Text(
                            text = readout,
                            color = if (uiState.isReferenceAuditioning) NeonCyan else TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .testTag("reference_readout")
                        )
                    }

                    if (showDiagnostics) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(Radius.lg))
                                .background(com.example.ui.theme.PanelMuted)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.lg))
                                .clickable(role = Role.Button) { onCalibrateLatency() }
                                .padding(horizontal = 8.dp)
                                .testTag("engine_telemetry"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = stringResource(R.string.transport_engine_telemetry),
                            tint = if (uiState.telemetry.bufferUnderruns > 0) StudioAmber else ElectricLime,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                R.string.transport_latency_value,
                                uiState.telemetry.callbackP95Ms
                            ),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        if (uiState.telemetry.bufferUnderruns > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = pluralStringResource(
                                    R.plurals.transport_glitch_hint,
                                    uiState.telemetry.bufferUnderruns,
                                    uiState.telemetry.bufferUnderruns
                                ),
                                color = StudioAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                    }
                }
            }

            AnimatedVisibility(visible = uiState.statusMessage != null) {
                uiState.statusMessage?.let { msg ->
                    val isFailure = uiState.statusKind == StatusKind.ERROR
                    val isSuccess = uiState.statusKind == StatusKind.SUCCESS
                    val accent = when {
                        isFailure -> ClipRed
                        isSuccess -> ElectricLime
                        else -> NeonCyan
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("status_banner"),
                        color = accent.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(Radius.md),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg,
                                color = if (isFailure) ClipRed else TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            uiState.statusAction
                                ?.takeIf { it.forMessage == null || it.forMessage == msg }
                                ?.let { action ->
                                TextButton(
                                    onClick = onStatusAction,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("status_action_button")
                                ) {
                                    Text(
                                        text = action.label,
                                        color = accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismissStatus,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("dismiss_status_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.transport_dismiss_status),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportToolButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    testTag: String
) {
    val color = when {
        !enabled -> TextMuted.copy(alpha = 0.55f)
        selected -> NeonCyan
        else -> TextSecondary
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
