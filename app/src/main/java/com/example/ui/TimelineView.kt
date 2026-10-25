package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.model.AudioClip
import com.example.audio.model.DawSettings
import com.example.audio.model.EditMode
import com.example.audio.model.FadeCurve
import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.example.ui.components.AudioRotaryKnob
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
import kotlin.math.sin

@Composable
fun TimelineView(
    session: ProjectSession,
    uiState: EngineUiState,
    settings: DawSettings = DawSettings(),
    onSeekFraction: (Float) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSplitClipAtPlayhead: (Int) -> Unit,
    onTrimClipEnd: (Int, String) -> Unit,
    onDuplicateClip: (Int, String) -> Unit,
    onOpenClipBin: () -> Unit,
    onToggleMagneticSnap: () -> Unit = {},
    onCycleGridResolution: () -> Unit = {},
    onNudgeClip: (Int, String, Boolean) -> Unit = { _, _, _ -> },
    onSetClipGain: (Int, String, Float) -> Unit = { _, _, _ -> },
    onSetClipFadeIn: (Int, String, Float, FadeCurve) -> Unit = { _, _, _, _ -> },
    onSetClipFadeOut: (Int, String, Float, FadeCurve) -> Unit = { _, _, _, _ -> },
    onPasteChorusSection: (Int, String, Int) -> Unit = { _, _, _ -> },
    onPasteChorusDouble: (Int, String, Int) -> Unit = { _, _, _ -> },
    onAdaptChorusLoop: (Int, String, Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val totalFrames = session.totalLengthFrames.toFloat().coerceAtLeast(1f)
    val playheadFraction = (uiState.playheadFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
    val selectedTrack = session.tracks.getOrNull(uiState.selectedTrackIndex)
    val firstSelectedClip = selectedTrack?.clips?.firstOrNull()

    var showClipAdjustModal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(8.dp)
            .testTag("timeline_view")
    ) {
        // Magnetic Grid Controls & Quick Action Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Interactive Magnetic Snap Toggle Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (settings.isMagneticSnapEnabled) ElectricLime.copy(alpha = 0.2f) else StudioCard)
                        .border(1.dp, if (settings.isMagneticSnapEnabled) ElectricLime else StudioCardBorder, RoundedCornerShape(4.dp))
                        .clickable { onToggleMagneticSnap() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("toggle_magnetic_snap_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (settings.isMagneticSnapEnabled) "SNAP: ON" else "SNAP: OFF",
                        color = if (settings.isMagneticSnapEnabled) ElectricLime else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Grid Resolution Selector Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioSurface)
                        .border(1.dp, NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .clickable { onCycleGridResolution() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("cycle_grid_resolution_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "GRID: ${settings.gridResolution.displayName.uppercase()}",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Trk ${uiState.selectedTrackIndex + 1}: ${selectedTrack?.name ?: ""}",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // CLIP FADES & ADJUST MODAL BUTTON
                if (firstSelectedClip != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(StudioPurple.copy(alpha = 0.25f))
                            .border(1.dp, StudioPurple, RoundedCornerShape(4.dp))
                            .clickable { showClipAdjustModal = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("open_clip_adjust_modal"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = "Fades", tint = StudioPurple, modifier = Modifier.size(12.dp))
                            Text("FADES & GAIN", color = StudioPurple, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Nudge Clip Backward
                IconButton(
                    onClick = {
                        if (firstSelectedClip != null) {
                            onNudgeClip(uiState.selectedTrackIndex, firstSelectedClip.id, false)
                        }
                    },
                    enabled = firstSelectedClip != null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioCard)
                        .testTag("nudge_clip_prev")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Nudge Left",
                        tint = if (firstSelectedClip != null) StudioAmber else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Nudge Clip Forward
                IconButton(
                    onClick = {
                        if (firstSelectedClip != null) {
                            onNudgeClip(uiState.selectedTrackIndex, firstSelectedClip.id, true)
                        }
                    },
                    enabled = firstSelectedClip != null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioCard)
                        .testTag("nudge_clip_next")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Nudge Right",
                        tint = if (firstSelectedClip != null) StudioAmber else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Clip List / Region Bin Button
                IconButton(
                    onClick = onOpenClipBin,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioCard)
                        .testTag("open_clip_bin_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Clip Bin",
                        tint = ElectricLime,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Selected Clip Edit Action Bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp)),
            color = StudioSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (firstSelectedClip != null) "CLIP: ${firstSelectedClip.name}" else "No Clip Selected",
                    color = if (firstSelectedClip != null) NeonCyan else TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onSplitClipAtPlayhead(uiState.selectedTrackIndex) },
                        enabled = firstSelectedClip != null,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCard),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("split_clip_button")
                    ) {
                        Icon(Icons.Default.CallSplit, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SPLIT", fontSize = 10.sp, color = NeonCyan)
                    }

                    Button(
                        onClick = {
                            if (firstSelectedClip != null) {
                                onTrimClipEnd(uiState.selectedTrackIndex, firstSelectedClip.id)
                            }
                        },
                        enabled = firstSelectedClip != null,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCard),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("trim_clip_button")
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = StudioAmber, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TRIM", fontSize = 10.sp, color = StudioAmber)
                    }

                    Button(
                        onClick = {
                            if (firstSelectedClip != null) {
                                onDuplicateClip(uiState.selectedTrackIndex, firstSelectedClip.id)
                            }
                        },
                        enabled = firstSelectedClip != null,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCard),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("duplicate_clip_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ElectricLime, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DUP", fontSize = 10.sp, color = ElectricLime)
                    }
                }
            }
        }

        // Timeline Header Ruler (Bars / Seconds)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(Color(0xFF0F1420))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val totalSec = (session.totalLengthFrames / session.sampleRate.toDouble()).toFloat()

                // Draw Bar tick marks
                val bars = 16
                for (b in 0..bars) {
                    val x = (b.toFloat() / bars) * w
                    drawLine(
                        color = Color(0xFF475569),
                        start = Offset(x, h - 8f),
                        end = Offset(x, h),
                        strokeWidth = 1.5f
                    )
                }

                // Draw Playhead cursor triangle
                val headX = w * playheadFraction
                val cursorPath = Path().apply {
                    moveTo(headX - 6f, 0f)
                    lineTo(headX + 6f, 0f)
                    lineTo(headX, 10f)
                    close()
                }
                drawPath(cursorPath, color = NeonCyan)
            }
        }

        // 16 Track Lanes with Waveform Clips & Playhead Overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .background(Color(0xFF090C12))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(session.tracks) { index, track ->
                    TrackLane(
                        track = track,
                        isSelected = uiState.selectedTrackIndex == index,
                        totalFrames = session.totalLengthFrames,
                        settings = settings,
                        onSelect = { onSelectTrack(index) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                    )
                }
            }

            // Playhead Vertical Line traversing all tracks
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeekFraction(fraction)
                        }
                    }
            ) {
                val x = size.width * playheadFraction
                drawLine(
                    color = NeonCyan,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
            }
        }
    }

    // Clip Adjustment Modal Dialog
    if (showClipAdjustModal && firstSelectedClip != null && selectedTrack != null) {
        ClipAdjustModal(
            clip = firstSelectedClip,
            track = selectedTrack,
            onDismiss = { showClipAdjustModal = false },
            onSetGain = { g -> onSetClipGain(selectedTrack.index, firstSelectedClip.id, g) },
            onSetFadeIn = { ms, curve -> onSetClipFadeIn(selectedTrack.index, firstSelectedClip.id, ms, curve) },
            onSetFadeOut = { ms, curve -> onSetClipFadeOut(selectedTrack.index, firstSelectedClip.id, ms, curve) },
            onPasteChorusSection = { bars ->
                onPasteChorusSection(selectedTrack.index, firstSelectedClip.id, bars)
                showClipAdjustModal = false
            },
            onPasteChorusDouble = {
                onPasteChorusDouble(selectedTrack.index, firstSelectedClip.id, (selectedTrack.index + 1) % 16)
                showClipAdjustModal = false
            },
            onAdaptChorusLoop = {
                onAdaptChorusLoop(selectedTrack.index, firstSelectedClip.id, 3)
                showClipAdjustModal = false
            }
        )
    }
}

@Composable
fun TrackLane(
    track: TrackData,
    isSelected: Boolean,
    totalFrames: Long,
    settings: DawSettings,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(if (isSelected) Color(0xFF141A26) else StudioSurface)
            .border(0.5.dp, if (isSelected) NeonCyan.copy(alpha = 0.5f) else StudioCardBorder)
            .clickable { onSelect() }
            .testTag("track_lane_${track.index}")
    ) {
        // Track Header (left label)
        Row(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .background(Color(0xFF0F141E))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(track.colorHex))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "${track.index + 1}. ${track.name}",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.stemCategory.displayName} • ${if (track.isMuted) "MUTED" else "ACTIVE"}",
                    color = if (track.isMuted) TextMuted else NeonCyan,
                    fontSize = 9.sp
                )
            }
        }

        // Clip Track Area with Magnetic Grid Guides and Visual Fades
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val laneWidth = size.width
                val laneHeight = size.height

                // Draw Magnetic Grid Guides
                val divisions = when (settings.gridResolution) {
                    GridResolution.BAR -> 8
                    GridResolution.BEAT -> 32
                    GridResolution.SIXTEENTH -> 64
                    GridResolution.CLIP_BOUNDARIES -> 8
                }

                for (b in 1 until divisions) {
                    val x = b * (laneWidth / divisions)
                    val isMajorBar = b % (divisions / 8) == 0
                    drawLine(
                        color = if (isMajorBar) Color(0xFF2D3748) else Color(0xFF171E2D),
                        start = Offset(x, 0f),
                        end = Offset(x, laneHeight),
                        strokeWidth = if (isMajorBar) 1f else 0.5f
                    )
                }

                // Render clips as waveform boxes with visual fade ramps
                for (clip in track.clips) {
                    val clipStartFrac = (clip.timelineStartFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
                    val clipLenFrac = (clip.lengthFrames.toFloat() / totalFrames).coerceIn(0.01f, 1f)

                    val clipX = clipStartFrac * laneWidth
                    val clipW = clipLenFrac * laneWidth

                    // Clip background
                    val clipColor = Color(track.colorHex).copy(alpha = if (track.isMuted) 0.25f else 0.8f)
                    drawRoundRect(
                        color = clipColor,
                        topLeft = Offset(clipX, 2f),
                        size = Size(clipW, laneHeight - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )

                    // Draw real waveform peaks derived from audio data
                    val peaks = com.example.audio.engine.WaveformCache.get(clip.id)
                    val waveSteps = if (peaks != null) peaks.size else Math.max(10, (clipW / 3f).toInt())
                    val centerY = laneHeight / 2f
                    if (peaks != null) {
                        for (b in peaks.indices) {
                            val px = clipX + (b.toFloat() / peaks.size) * clipW
                            val minV = peaks[b][0]
                            val maxV = peaks[b][1]
                            val top = centerY - maxV.coerceIn(-1f, 1f) * (laneHeight - 12f) * 0.5f
                            val bot = centerY - minV.coerceIn(-1f, 1f) * (laneHeight - 12f) * 0.5f
                            drawLine(
                                color = Color.White.copy(alpha = 0.85f),
                                start = Offset(px, top),
                                end = Offset(px, bot),
                                strokeWidth = 1.2f
                            )
                        }
                    }

                    // 1. Visual Fade-In Ramp Overlay
                    if (clip.fadeInFrames > 0L) {
                        val fadeInFrac = (clip.fadeInFrames.toFloat() / totalFrames).coerceIn(0f, clipLenFrac)
                        val fadeInWidth = fadeInFrac * laneWidth

                        val fadePath = Path().apply {
                            moveTo(clipX, laneHeight - 2f)
                            lineTo(clipX + fadeInWidth, 2f)
                            lineTo(clipX, 2f)
                            close()
                        }
                        drawPath(fadePath, color = Color.Black.copy(alpha = 0.45f))
                        drawLine(
                            color = ElectricLime,
                            start = Offset(clipX, laneHeight - 2f),
                            end = Offset(clipX + fadeInWidth, 2f),
                            strokeWidth = 2f
                        )
                    }

                    // 2. Visual Fade-Out Ramp Overlay
                    if (clip.fadeOutFrames > 0L) {
                        val fadeOutFrac = (clip.fadeOutFrames.toFloat() / totalFrames).coerceIn(0f, clipLenFrac)
                        val fadeOutWidth = fadeOutFrac * laneWidth

                        val fadeOutPath = Path().apply {
                            moveTo(clipX + clipW, laneHeight - 2f)
                            lineTo(clipX + clipW - fadeOutWidth, 2f)
                            lineTo(clipX + clipW, 2f)
                            close()
                        }
                        drawPath(fadeOutPath, color = Color.Black.copy(alpha = 0.45f))
                        drawLine(
                            color = StudioAmber,
                            start = Offset(clipX + clipW, laneHeight - 2f),
                            end = Offset(clipX + clipW - fadeOutWidth, 2f),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClipAdjustModal(
    clip: AudioClip,
    track: TrackData,
    onDismiss: () -> Unit,
    onSetGain: (Float) -> Unit,
    onSetFadeIn: (Float, FadeCurve) -> Unit,
    onSetFadeOut: (Float, FadeCurve) -> Unit,
    onPasteChorusSection: (Int) -> Unit,
    onPasteChorusDouble: () -> Unit,
    onAdaptChorusLoop: () -> Unit
) {
    var gainDb by remember { mutableStateOf(clip.gainDb) }
    var fadeInMs by remember { mutableStateOf((clip.fadeInFrames / 48f)) }
    var fadeOutMs by remember { mutableStateOf((clip.fadeOutFrames / 48f)) }
    var selectedCurve by remember { mutableStateOf(clip.fadeInCurve) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Modal Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("CLIP INSPECTOR & FADES", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("${clip.name} • Trk ${track.index + 1}", color = TextMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Rotary Knobs Row for Gain, Fade-In, Fade-Out
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AudioRotaryKnob(
                        value = gainDb,
                        onValueChange = {
                            gainDb = it
                            onSetGain(it)
                        },
                        range = -24f..12f,
                        label = "Clip Gain",
                        accentColor = StudioAmber,
                        defaultValue = 0f,
                        valueFormatter = { "%+.1f dB".format(it) }
                    )

                    AudioRotaryKnob(
                        value = fadeInMs,
                        onValueChange = {
                            fadeInMs = it
                            onSetFadeIn(it, selectedCurve)
                        },
                        range = 0f..2000f,
                        label = "Fade In",
                        accentColor = ElectricLime,
                        defaultValue = 0f,
                        valueFormatter = { "%.0f ms".format(it) }
                    )

                    AudioRotaryKnob(
                        value = fadeOutMs,
                        onValueChange = {
                            fadeOutMs = it
                            onSetFadeOut(it, selectedCurve)
                        },
                        range = 0f..2000f,
                        label = "Fade Out",
                        accentColor = StudioPurple,
                        defaultValue = 0f,
                        valueFormatter = { "%.0f ms".format(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Fade Curve Selector
                Text("FADE CURVE ALGORITHM", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FadeCurve.values().forEach { curve ->
                        val isSel = selectedCurve == curve
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) NeonCyan.copy(alpha = 0.2f) else StudioCard)
                                .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(4.dp))
                                .clickable {
                                    selectedCurve = curve
                                    onSetFadeIn(fadeInMs, curve)
                                    onSetFadeOut(fadeOutMs, curve)
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = curve.displayName.substringBefore(" "),
                                color = if (isSel) NeonCyan else TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Fast Chorus Pasting Actions
                Text("CHORUS ADAPTING & FAST PASTING", color = StudioPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onPasteChorusSection(4) },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioPurple),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚡ Chorus +4B", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onPasteChorusDouble() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("👥 Doubler Layer", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onAdaptChorusLoop() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔁 4-Bar Loop", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
