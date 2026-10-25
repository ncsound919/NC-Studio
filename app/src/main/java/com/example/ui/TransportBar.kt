package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.model.ProjectSession
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

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
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transport_bar"),
        color = StudioSurface,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Timecode & Tempo display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF070A0F))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.timeFormatted,
                            color = NeonCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("timecode_display")
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioCard)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${session.tempoBpm.toInt()} BPM",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Transport Play / Stop / Record Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Loop toggle
                    val loopColor by animateColorAsState(
                        if (session.loopEnabled) NeonCyan else TextMuted,
                        label = "loop_color"
                    )
                    IconButton(
                        onClick = onToggleLoop,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("transport_loop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Loop,
                            contentDescription = "Toggle Loop",
                            tint = loopColor
                        )
                    }

                    // Stop button
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioCard)
                            .testTag("transport_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = TextPrimary
                        )
                    }

                    // Play / Pause button
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isPlaying) ElectricLime else NeonCyan)
                            .testTag("transport_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            tint = Color.Black
                        )
                    }

                    // Record button
                    val recBg by animateColorAsState(
                        if (uiState.isRecording) ClipRed else StudioCard,
                        label = "rec_bg"
                    )
                    IconButton(
                        onClick = onToggleRecord,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(recBg)
                            .testTag("transport_record_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = "Record",
                            tint = if (uiState.isRecording) Color.White else ClipRed
                        )
                    }
                }
                // Overdubbing punch in/out + A/B reference controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    androidx.compose.material3.TextButton(onClick = onSetPunchIn) {
                        Text("Punch In", color = if (uiState.punchInFrame != null) ElectricLime else TextMuted, fontSize = 11.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = onSetPunchOut) {
                        Text("Punch Out", color = if (uiState.punchOutFrame != null) ElectricLime else TextMuted, fontSize = 11.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = onClearPunch) {
                        Text("Clear Punch", color = TextMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onToggleAb) {
                        Text(if (uiState.isAbComparing) "A/B: ON" else "A/B", color = if (uiState.isAbComparing) NeonCyan else TextMuted, fontSize = 11.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = onLoadReference) {
                        Text("Load Ref", color = TextMuted, fontSize = 11.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = onMatchToReference) {
                        Text("Match", color = TextMuted, fontSize = 11.sp)
                    }
                }

                // Undo / Redo & Telemetry
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onUndo,
                        enabled = canUndo,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("undo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) TextPrimary else TextMuted
                        )
                    }

                    IconButton(
                        onClick = onRedo,
                        enabled = canRedo,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("redo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) TextPrimary else TextMuted
                        )
                    }

                    // Real-time Audio Engine Health pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F1522))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp))
                            .clickable { onCalibrateLatency() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Engine Telemetry",
                            tint = if (uiState.telemetry.bufferUnderruns > 0) StudioAmber else ElectricLime,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${String.format("%.1f", uiState.telemetry.callbackP95Ms)}ms",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Status message toast banner
            AnimatedVisibility(visible = uiState.statusMessage != null) {
                uiState.statusMessage?.let { msg ->
                    Text(
                        text = "• $msg",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
