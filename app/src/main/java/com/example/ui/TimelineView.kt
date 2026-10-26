package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.audio.model.AudioClip
import com.example.audio.model.DawSettings
import com.example.audio.model.FadeCurve
import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.example.ui.components.AudioRotaryKnob
import com.example.ui.components.StudioTooltip
import com.example.ui.theme.Radius
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.GridMajor
import com.example.ui.theme.GridMinor
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PanelDeep
import com.example.ui.theme.PanelInset
import com.example.ui.theme.RulerTick
import com.example.ui.theme.TrackHeader
import com.example.ui.theme.TrackLaneBase
import com.example.ui.theme.TrackLaneSelected
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.ceil

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
    onSetLoopRange: (Long, Long) -> Unit = { _, _ -> },
    onSetPunchRange: (Long, Long) -> Unit = { _, _ -> },
    onToggleClipSelection: (Int, String) -> Unit = { _, _ -> },
    onClearClipSelection: () -> Unit = {},
    onMoveClip: (Int, String, Long) -> Unit = { _, _, _ -> },
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
    val selectedClips = selectedTrack?.clips
        ?.filter { it.id in uiState.selectedClipIds }
        .orEmpty()
    val firstSelectedClip = selectedClips.firstOrNull()
        ?: selectedTrack?.clips?.firstOrNull()
    val activeClips = selectedClips.ifEmpty {
        selectedTrack?.clips?.firstOrNull()?.let { listOf(it) }.orEmpty()
    }
    val framesPerBeat = session.sampleRate * 60.0 / session.tempoBpm.coerceAtLeast(1f)
    val framesPerBar = framesPerBeat * 4.0

    val haptics = LocalHapticFeedback.current
    var showClipAdjustModal by remember { mutableStateOf(false) }
    var snapFlashFrame by remember { mutableStateOf<Long?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val contentScroll = rememberScrollState()
    val trackHeaderWidth = 110.dp

    // A brief snap guide confirms exactly where an edit landed without adding
    // permanent chrome to the arrangement.
    LaunchedEffect(snapFlashFrame) {
        if (snapFlashFrame != null) {
            delay(360)
            snapFlashFrame = null
        }
    }

    fun markEditLanded() {
        activeClips.firstOrNull()?.let { snapFlashFrame = it.timelineStartFrame }
    }

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
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Interactive Magnetic Snap Toggle Button
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (settings.isMagneticSnapEnabled) ElectricLime.copy(alpha = 0.2f) else StudioCard)
                    .border(1.dp, if (settings.isMagneticSnapEnabled) ElectricLime else StudioCardBorder, RoundedCornerShape(Radius.md))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        haptics.studioConfirm(settings.enableHapticFeedback)
                        onToggleMagneticSnap()
                    }
                    .semantics {
                        stateDescription = if (settings.isMagneticSnapEnabled) "Snap on" else "Snap off"
                    }
                    .padding(horizontal = 8.dp)
                    .testTag("toggle_magnetic_snap_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (settings.isMagneticSnapEnabled) "SNAP ON" else "SNAP OFF",
                    color = if (settings.isMagneticSnapEnabled) ElectricLime else TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Grid Resolution Selector Badge
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioSurface)
                    .border(1.dp, NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(Radius.md))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        haptics.studioTick(settings.enableHapticFeedback)
                        onCycleGridResolution()
                    }
                    .semantics {
                        stateDescription = "Grid ${settings.gridResolution.displayName}"
                    }
                    .padding(horizontal = 8.dp)
                    .testTag("cycle_grid_resolution_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "GRID ${settings.gridResolution.displayName.uppercase()}",
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Zoom keeps the whole session visible at 1× and stretches the
            // arrangement without changing clip data.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudioTooltip("Zoom out") {
                    IconButton(
                        onClick = {
                            haptics.studioTick(settings.enableHapticFeedback)
                            zoom = (zoom / 1.5f).coerceAtLeast(1f)
                        },
                        enabled = zoom > 1.01f,
                        modifier = Modifier.size(48.dp).testTag("timeline_zoom_out")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Zoom timeline out",
                            tint = if (zoom > 1.01f) TextPrimary else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(StudioCard)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            haptics.studioTick(settings.enableHapticFeedback)
                            zoom = 1f
                        }
                        .padding(horizontal = 6.dp)
                        .testTag("timeline_fit_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FitScreen,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (zoom <= 1.01f) "FIT" else "x${String.format("%.1f", zoom)}",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                StudioTooltip("Zoom in") {
                    IconButton(
                        onClick = {
                            haptics.studioTick(settings.enableHapticFeedback)
                            zoom = (zoom * 1.5f).coerceAtMost(8f)
                        },
                        enabled = zoom < 7.99f,
                        modifier = Modifier.size(48.dp).testTag("timeline_zoom_in")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Zoom timeline in",
                            tint = if (zoom < 7.99f) TextPrimary else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (selectedClips.size > 1) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(ElectricLime.copy(alpha = 0.18f))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClearClipSelection() }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("clip_selection_count"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedClips.size} clips",
                        color = ElectricLime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = "Trk ${uiState.selectedTrackIndex + 1}: ${selectedTrack?.name ?: ""}",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenClipBin,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("open_clip_bin_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Open clip bin",
                    tint = ElectricLime,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (firstSelectedClip != null) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(StudioPurple.copy(alpha = 0.25f))
                        .border(1.dp, StudioPurple, RoundedCornerShape(Radius.md))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { showClipAdjustModal = true }
                        .padding(horizontal = 10.dp)
                        .testTag("open_clip_adjust_modal"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = StudioPurple, modifier = Modifier.size(14.dp))
                        Text(
                            text = "${firstSelectedClip.name} • Fades",
                            color = StudioPurple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                    }
                }
            }

            StudioTooltip("Split clip at playhead") {
            IconButton(
                onClick = {
                    onSplitClipAtPlayhead(uiState.selectedTrackIndex)
                    markEditLanded()
                },
                enabled = firstSelectedClip != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("split_clip_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Split clip at playhead",
                    tint = if (firstSelectedClip != null) NeonCyan else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            }

            StudioTooltip("Trim clip end") {
            IconButton(
                onClick = {
                    activeClips.forEach { onTrimClipEnd(uiState.selectedTrackIndex, it.id) }
                    if (activeClips.isNotEmpty()) markEditLanded()
                },
                enabled = firstSelectedClip != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("trim_clip_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCut,
                    contentDescription = "Trim clip end",
                    tint = if (firstSelectedClip != null) StudioAmber else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            }

            StudioTooltip("Duplicate clip") {
            IconButton(
                onClick = {
                    activeClips.forEach { onDuplicateClip(uiState.selectedTrackIndex, it.id) }
                    if (activeClips.isNotEmpty()) markEditLanded()
                },
                enabled = firstSelectedClip != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("duplicate_clip_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Duplicate clip",
                    tint = if (firstSelectedClip != null) ElectricLime else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            }

            StudioTooltip("Nudge clip left") {
            IconButton(
                onClick = {
                    activeClips.forEach { onNudgeClip(uiState.selectedTrackIndex, it.id, false) }
                    if (activeClips.isNotEmpty()) markEditLanded()
                },
                enabled = firstSelectedClip != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("nudge_clip_prev")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Nudge clip left",
                    tint = if (firstSelectedClip != null) StudioAmber else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            }

            StudioTooltip("Nudge clip right") {
            IconButton(
                onClick = {
                    activeClips.forEach { onNudgeClip(uiState.selectedTrackIndex, it.id, true) }
                    if (activeClips.isNotEmpty()) markEditLanded()
                },
                enabled = firstSelectedClip != null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .testTag("nudge_clip_next")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Nudge clip right",
                    tint = if (firstSelectedClip != null) StudioAmber else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            }
        }

        // Session overview: full arrangement density with the visible window and playhead.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(bottom = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(trackHeaderWidth)
                    .fillMaxHeight()
                    .background(PanelDeep),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.ui_overview), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(PanelInset)
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.sm))
                    .testTag("timeline_minimap")
                    .semantics { contentDescription = "Session overview, tap to seek" }
                    .pointerInput(session.totalLengthFrames, zoom) {
                        detectTapGestures { offset ->
                            onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val contentWidthPx = with(density) { (maxWidth * zoom).toPx() }.coerceAtLeast(1f)
                val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val total = session.totalLengthFrames.coerceAtLeast(1L).toFloat()
                    val laneHeight = size.height / session.tracks.size.coerceAtLeast(1)

                    session.tracks.forEachIndexed { index, track ->
                        track.clips.forEach { clip ->
                            val left = clip.timelineStartFrame / total * size.width
                            val width = (clip.lengthFrames / total * size.width).coerceAtLeast(2f)
                            drawRect(
                                color = if (index == uiState.selectedTrackIndex) NeonCyan.copy(alpha = 0.75f) else ElectricLime.copy(alpha = 0.5f),
                                topLeft = Offset(left, index * laneHeight + 1f),
                                size = Size(width, (laneHeight - 2f).coerceAtLeast(1f))
                            )
                        }
                    }

                    if (session.loopEnabled) {
                        val start = session.loopStartFrame / total * size.width
                        val end = session.loopEndFrame / total * size.width
                        drawRect(
                            color = NeonCyan.copy(alpha = 0.14f),
                            topLeft = Offset(start, 0f),
                            size = Size((end - start).coerceAtLeast(0f), size.height)
                        )
                    }

                    val scrollFraction = contentScroll.value / contentWidthPx
                    val windowLeft = scrollFraction * size.width
                    val windowWidth = (viewportWidthPx / contentWidthPx * size.width).coerceAtMost(size.width)
                    drawRect(
                        color = TextSecondary.copy(alpha = 0.9f),
                        topLeft = Offset(windowLeft, 0f),
                        size = Size(windowWidth, size.height),
                        style = Stroke(width = 1.5f)
                    )

                    val playheadX = uiState.playheadFrame / total * size.width
                    drawLine(
                        color = NeonCyan,
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, size.height),
                        strokeWidth = 1.5f
                    )
                }
            }
        }

        // Timeline Header Ruler: bars are derived from tempo, not a fixed cell count.
        Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Spacer(
                modifier = Modifier
                    .width(trackHeaderWidth)
                    .fillMaxHeight()
                    .background(PanelDeep)
                    .border(1.dp, StudioCardBorder)
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = Radius.md))
                    .background(PanelDeep)
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(topEnd = Radius.md))
                    .testTag("timeline_ruler")
            ) {
                val rulerWidth = maxWidth * zoom
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(contentScroll)
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(rulerWidth)
                            .fillMaxHeight()
                            .pointerInput(zoom) {
                                detectTapGestures { offset ->
                                    onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val total = session.totalLengthFrames.coerceAtLeast(1L).toDouble()
                        val barPx = w * (framesPerBar / total)
                        val barCount = ceil(total / framesPerBar).toInt().coerceAtMost(1_000)
                        val labelStep = maxOf(1, ceil(44f / barPx.toFloat()).toInt())
                        val labelPaint = android.graphics.Paint().apply {
                            color = TextSecondary.toArgb()
                            textSize = 9.sp.toPx()
                            isAntiAlias = true
                        }

                        for (bar in 0..barCount step labelStep) {
                            val x = (bar * framesPerBar / total * w).toFloat()
                            drawLine(
                                color = RulerTick,
                                start = Offset(x, h - 7f),
                                end = Offset(x, h),
                                strokeWidth = 1.5f
                            )
                            if (bar > 0) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    "${bar + 1}",
                                    x + 3f,
                                    11.sp.toPx(),
                                    labelPaint
                                )
                            }
                        }

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

                // Loop/punch edges live in the ruler so their 48dp drag targets
                // never cover clips or fade grips in the arrangement.
                val density = androidx.compose.ui.platform.LocalDensity.current
                val contentWidthPx = with(density) { (maxWidth * zoom).toPx() }.coerceAtLeast(1f)
                val framesPerPixel = session.totalLengthFrames.toFloat() / contentWidthPx
                val scrollPx = contentScroll.value.toFloat()

                if (session.loopEnabled) {
                    val startX = with(density) {
                        (session.loopStartFrame / session.totalLengthFrames.toFloat() * contentWidthPx - scrollPx).toDp()
                    }
                    val endX = with(density) {
                        (session.loopEndFrame / session.totalLengthFrames.toFloat() * contentWidthPx - scrollPx).toDp()
                    }
                    RangeHandle(
                        centerX = startX,
                        viewportWidth = maxWidth,
                        accentColor = NeonCyan,
                        label = "Loop start",
                        testTag = "loop_start_handle",
                        hapticsEnabled = settings.enableHapticFeedback,
                        onDrag = { deltaPx ->
                            onSetLoopRange(
                                session.loopStartFrame + (deltaPx * framesPerPixel).toLong(),
                                session.loopEndFrame
                            )
                        }
                    )
                    RangeHandle(
                        centerX = endX,
                        viewportWidth = maxWidth,
                        accentColor = NeonCyan,
                        label = "Loop end",
                        testTag = "loop_end_handle",
                        hapticsEnabled = settings.enableHapticFeedback,
                        onDrag = { deltaPx ->
                            onSetLoopRange(
                                session.loopStartFrame,
                                session.loopEndFrame + (deltaPx * framesPerPixel).toLong()
                            )
                        }
                    )
                }

                val punchIn = uiState.punchInFrame
                val punchOut = uiState.punchOutFrame
                if (punchIn != null) {
                    val x = with(density) {
                        (punchIn / session.totalLengthFrames.toFloat() * contentWidthPx - scrollPx).toDp()
                    }
                    RangeHandle(
                        centerX = x,
                        viewportWidth = maxWidth,
                        accentColor = StudioAmber,
                        label = "Punch in point",
                        testTag = "punch_in_handle",
                        hapticsEnabled = settings.enableHapticFeedback,
                        onDrag = { deltaPx ->
                            onSetPunchRange(
                                punchIn + (deltaPx * framesPerPixel).toLong(),
                                punchOut ?: session.totalLengthFrames
                            )
                        }
                    )
                }
                if (punchOut != null) {
                    val x = with(density) {
                        (punchOut / session.totalLengthFrames.toFloat() * contentWidthPx - scrollPx).toDp()
                    }
                    RangeHandle(
                        centerX = x,
                        viewportWidth = maxWidth,
                        accentColor = StudioAmber,
                        label = "Punch out point",
                        testTag = "punch_out_handle",
                        hapticsEnabled = settings.enableHapticFeedback,
                        onDrag = { deltaPx ->
                            onSetPunchRange(
                                punchIn ?: 0L,
                                punchOut + (deltaPx * framesPerPixel).toLong()
                            )
                        }
                    )
                }
            }
        }

        // 16 Track Lanes with Waveform Clips & Playhead Overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md))
                .background(TrackLaneBase)
                .drawWithContent {
                    drawContent()
                    val headerPx = trackHeaderWidth.toPx()
                    val contentPx = (size.width - headerPx).coerceAtLeast(1f) * zoom
                    val scrollPx = contentScroll.value.toFloat()
                    val total = session.totalLengthFrames.coerceAtLeast(1L).toFloat()
                    fun frameToX(frame: Long): Float =
                        headerPx + (frame / total * contentPx) - scrollPx

                    clipRect(left = headerPx, right = size.width) {
                        if (session.loopEnabled) {
                            val start = frameToX(session.loopStartFrame)
                            val end = frameToX(session.loopEndFrame)
                            if (end > start) {
                                drawRect(
                                    color = NeonCyan.copy(alpha = 0.06f),
                                    topLeft = Offset(start, 0f),
                                    size = Size(end - start, size.height)
                                )
                                drawLine(
                                    color = NeonCyan.copy(alpha = 0.55f),
                                    start = Offset(start, 0f),
                                    end = Offset(start, size.height),
                                    strokeWidth = 1f
                                )
                                drawLine(
                                    color = NeonCyan.copy(alpha = 0.55f),
                                    start = Offset(end, 0f),
                                    end = Offset(end, size.height),
                                    strokeWidth = 1f
                                )
                            }
                        }

                        val punchIn = uiState.punchInFrame
                        val punchOut = uiState.punchOutFrame
                        if (punchIn != null || punchOut != null) {
                            val start = frameToX(punchIn ?: 0L)
                            val end = frameToX(punchOut ?: session.totalLengthFrames)
                            if (end > start) {
                                drawRect(
                                    color = StudioAmber.copy(alpha = 0.08f),
                                    topLeft = Offset(start, 0f),
                                    size = Size(end - start, size.height)
                                )
                            }
                        }

                        snapFlashFrame?.let { snapFrame ->
                            val snapX = frameToX(snapFrame)
                            drawLine(
                                color = ElectricLime.copy(alpha = 0.8f),
                                start = Offset(snapX, 0f),
                                end = Offset(snapX, size.height),
                                strokeWidth = 2f
                            )
                            drawCircle(
                                color = ElectricLime,
                                radius = 5f,
                                center = Offset(snapX, 6f)
                            )
                        }

                        val x = headerPx + (uiState.playheadFrame / total * contentPx) - scrollPx
                        drawLine(
                            color = NeonCyan.copy(alpha = 0.35f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 4f
                        )
                        drawLine(
                            color = NeonCyan,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2f
                        )
                        drawCircle(color = NeonCyan, radius = 4f, center = Offset(x, 4f))
                    }
                }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(session.tracks) { index, track ->
                    TrackLane(
                        track = track,
                        isSelected = uiState.selectedTrackIndex == index,
                        totalFrames = session.totalLengthFrames,
                        sampleRate = session.sampleRate,
                        tempoBpm = session.tempoBpm,
                        settings = settings,
                        selectedClipId = if (uiState.selectedTrackIndex == index) firstSelectedClip?.id else null,
                        selectedClipIds = uiState.selectedClipIds,
                        contentZoom = zoom,
                        scrollState = contentScroll,
                        onSelect = { onSelectTrack(index) },
                        onToggleClip = { clipId -> onToggleClipSelection(index, clipId) },
                        onMoveClip = { clipId, frame -> onMoveClip(index, clipId, frame) },
                        onClipDragStart = { snapFlashFrame = null },
                        onClipDragEnd = { markEditLanded() },
                        onFadeIn = { clipId, ms ->
                            val curve = track.clips.firstOrNull { it.id == clipId }?.fadeInCurve ?: FadeCurve.EQUAL_POWER
                            onSetClipFadeIn(index, clipId, ms, curve)
                            markEditLanded()
                        },
                        onFadeOut = { clipId, ms ->
                            val curve = track.clips.firstOrNull { it.id == clipId }?.fadeOutCurve ?: FadeCurve.EQUAL_POWER
                            onSetClipFadeOut(index, clipId, ms, curve)
                            markEditLanded()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                    )
                }
            }

            if (session.tracks.all { it.clips.isEmpty() }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StudioEmptyStateIllustration(
                            accent = NeonCyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = stringResource(R.string.ui_no_audio_yet),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = stringResource(R.string.ui_record_a_take_with_the_red_button_or_import_a_st),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

        }
    }

    // Clip Adjustment Modal Dialog
    if (showClipAdjustModal && firstSelectedClip != null) {
        val trackForModal = selectedTrack ?: return@TimelineView
        val clipForModal = firstSelectedClip
        ClipAdjustModal(
            clip = clipForModal,
            track = trackForModal,
            onDismiss = { showClipAdjustModal = false },
            onSetGain = { g -> onSetClipGain(trackForModal.index, clipForModal.id, g) },
            onSetFadeIn = { ms, curve -> onSetClipFadeIn(trackForModal.index, clipForModal.id, ms, curve) },
            onSetFadeOut = { ms, curve -> onSetClipFadeOut(trackForModal.index, clipForModal.id, ms, curve) },
            onPasteChorusSection = { bars ->
                onPasteChorusSection(trackForModal.index, clipForModal.id, bars)
                showClipAdjustModal = false
            },
            onPasteChorusDouble = {
                onPasteChorusDouble(trackForModal.index, clipForModal.id, (trackForModal.index + 1) % 16)
                showClipAdjustModal = false
            },
            onAdaptChorusLoop = {
                onAdaptChorusLoop(trackForModal.index, clipForModal.id, 3)
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
    sampleRate: Int,
    tempoBpm: Float,
    settings: DawSettings,
    selectedClipId: String? = null,
    selectedClipIds: Set<String> = emptySet(),
    contentZoom: Float = 1f,
    scrollState: ScrollState = rememberScrollState(),
    onSelect: () -> Unit,
    onToggleClip: (String) -> Unit = {},
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onClipDragStart: () -> Unit = {},
    onClipDragEnd: () -> Unit = {},
    onFadeIn: (String, Float) -> Unit = { _, _ -> },
    onFadeOut: (String, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val selectedClip = track.clips.firstOrNull { it.id == selectedClipId }
    val selectedClips = track.clips.filter { it.id in selectedClipIds }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .background(if (isSelected) TrackLaneSelected else StudioSurface)
            .border(0.5.dp, if (isSelected) NeonCyan.copy(alpha = 0.5f) else StudioCardBorder)                    .clickable { onSelect() }
                    .semantics {
                        stateDescription = if (isSelected) "Selected track" else "Not selected"
                    }
            .testTag("track_lane_${track.index}")
    ) {
        // Track Header (left label)
        Row(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .background(TrackHeader)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.sm))
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
                    fontSize = 10.sp
                )
            }
        }

        // Clip Track Area with Magnetic Grid Guides and Visual Fades
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp)
        ) {
            val viewportWidth = maxWidth
            val contentWidth = viewportWidth * contentZoom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState)
            ) {
                Box(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val laneWidth = size.width
                val laneHeight = size.height

                // Tempo-derived grid: bar lines always land on real bars.
                val total = totalFrames.coerceAtLeast(1L).toDouble()
                val beatFrames = sampleRate * 60.0 / tempoBpm.coerceAtLeast(1f)
                val barFrames = beatFrames * 4.0
                val barPx = laneWidth * (barFrames / total)
                val barStride = maxOf(1, ceil(8f / barPx.toFloat()).toInt())
                val barCount = ceil(total / barFrames).toInt().coerceAtMost(1_000)

                for (bar in 1..barCount step barStride) {
                    val x = (bar * barFrames / total * laneWidth).toFloat()
                    drawLine(
                        color = GridMajor,
                        start = Offset(x, 0f),
                        end = Offset(x, laneHeight),
                        strokeWidth = 1f
                    )
                }

                val subdivisionsPerBeat = when (settings.gridResolution) {
                    GridResolution.BEAT -> 1
                    GridResolution.SIXTEENTH -> 4
                    else -> 0
                }
                if (subdivisionsPerBeat > 0) {
                    val stepFrames = beatFrames / subdivisionsPerBeat
                    val stepPx = laneWidth * (stepFrames / total)
                    if (stepPx >= 8f) {
                        val stepCount = ceil(total / stepFrames).toInt().coerceAtMost(16_000)
                        for (step in 0..stepCount) {
                            if (step % (4 * subdivisionsPerBeat) == 0) continue // bar line already drawn
                            val x = (step * stepFrames / total * laneWidth).toFloat()
                            val isBeat = step % subdivisionsPerBeat == 0
                            drawLine(
                                color = if (isBeat) GridMinor else GridMinor.copy(alpha = 0.55f),
                                start = Offset(x, 0f),
                                end = Offset(x, laneHeight),
                                strokeWidth = 0.5f
                            )
                        }
                    }
                }

                if (settings.gridResolution == GridResolution.CLIP_BOUNDARIES) {
                    for (clip in track.clips) {
                        val startX = (clip.timelineStartFrame.toDouble() / total * laneWidth).toFloat()
                        val endX = ((clip.timelineStartFrame + clip.lengthFrames).toDouble() / total * laneWidth).toFloat()
                        drawLine(GridMajor, Offset(startX, 0f), Offset(startX, laneHeight), 1f)
                        drawLine(GridMajor, Offset(endX, 0f), Offset(endX, laneHeight), 1f)
                    }
                }

                // Render clips as waveform boxes with visual fade ramps
                for (clip in track.clips) {
                    val clipStartFrac = (clip.timelineStartFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
                    val clipLenFrac = (clip.lengthFrames.toFloat() / totalFrames).coerceIn(0.01f, 1f)

                    val clipX = clipStartFrac * laneWidth
                    val clipW = clipLenFrac * laneWidth
                    // Clip background (playing-clip highlight removed from per-lane canvas to
                    // avoid per-frame waveform redraws during playback; playhead is drawn by
                    // the track-lane container overlay instead).
                    val clipColor = Color(track.colorHex).copy(alpha = if (track.isMuted) 0.25f else 0.8f)
                    drawRoundRect(
                        color = clipColor,
                        topLeft = Offset(clipX, 2f),
                        size = Size(clipW, laneHeight - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )

                    if (clip.id in selectedClipIds || selectedClipId == clip.id) {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(clipX, 2f),
                            size = Size(clipW, laneHeight - 4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // Draw real waveform peaks; show a placeholder baseline while decoding.
                    val peaks = com.example.audio.engine.WaveformCache.get(clip.id)
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
                    } else {
                        drawLine(
                            color = Color.White.copy(alpha = 0.3f),
                            start = Offset(clipX + 2f, centerY),
                            end = Offset((clipX + clipW - 2f).coerceAtLeast(clipX + 2f), centerY),
                            strokeWidth = 1f
                        )
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

            // Transparent clip hit areas make multi-select possible without
            // drawing extra chrome. Fade grips sit above them.
            track.clips.forEach { clip ->
                val clipStartFrac = (clip.timelineStartFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
                val clipLenFrac = (clip.lengthFrames.toFloat() / totalFrames).coerceIn(0.01f, 1f)
                val isChosen = clip.id in selectedClipIds
                val contentWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    contentWidth.toPx()
                }.coerceAtLeast(1f)
                val framesPerPixel = totalFrames / contentWidthPx
                Box(
                    modifier = Modifier
                        .offset(x = contentWidth * clipStartFrac)
                        .width(contentWidth * clipLenFrac)
                        .fillMaxHeight()
                        .testTag("clip_${clip.id}")
                        .semantics {
                            contentDescription = "Clip ${clip.name}"
                            stateDescription = if (isChosen) "Selected" else "Not selected"
                        }
                        .clickable { onToggleClip(clip.id) }
                        .pointerInput(clip.id, contentWidthPx) {
                            var dragFrame = clip.timelineStartFrame
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragFrame = clip.timelineStartFrame
                                    haptics.studioConfirm(settings.enableHapticFeedback)
                                    onClipDragStart()
                                },
                                onDragCancel = { onClipDragEnd() },
                                onDragEnd = { onClipDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragFrame = (dragFrame + dragAmount.x * framesPerPixel).toLong()
                                        .coerceAtLeast(0L)
                                    onMoveClip(clip.id, dragFrame)
                                }
                            )
                        }
                )
            }

            // Fade handles stay on the selected clip(s). Each target is a
            // full 48dp square even though the visible grip is a 2dp line.
            (if (selectedClips.isNotEmpty()) selectedClips else listOfNotNull(selectedClip)).forEach { clip ->
                val clipStartFrac = (clip.timelineStartFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
                val clipLenFrac = (clip.lengthFrames.toFloat() / totalFrames).coerceIn(0.01f, 1f)
                val clipX = contentWidth * clipStartFrac
                val clipW = contentWidth * clipLenFrac
                val durationMs = (clip.lengthFrames / 48f).coerceAtLeast(1f)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val msPerPx = durationMs / with(density) { clipW.toPx() }.coerceAtLeast(1f)

                FadeDragHandle(
                    label = "Fade in",
                    valueMs = clip.fadeInFrames / 48f,
                    accentColor = ElectricLime,
                    msPerPixel = msPerPx,
                    maxMs = durationMs,
                    onValueChange = { onFadeIn(clip.id, it) },
                    onDragStart = { haptics.studioTick(settings.enableHapticFeedback) },
                    testTag = "fade_in_handle",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (clipX - 24.dp).coerceIn(0.dp, (contentWidth - 48.dp).coerceAtLeast(0.dp)))
                )

                FadeDragHandle(
                    label = "Fade out",
                    valueMs = clip.fadeOutFrames / 48f,
                    accentColor = StudioAmber,
                    msPerPixel = msPerPx,
                    maxMs = durationMs,
                    onValueChange = { onFadeOut(clip.id, it) },
                    onDragStart = { haptics.studioTick(settings.enableHapticFeedback) },
                    testTag = "fade_out_handle",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (clipX + clipW - 24.dp).coerceIn(0.dp, (contentWidth - 48.dp).coerceAtLeast(0.dp)))
                )
            }
                }
            }
        }
    }
}

@Composable
private fun FadeDragHandle(
    label: String,
    valueMs: Float,
    accentColor: Color,
    msPerPixel: Float,
    maxMs: Float,
    onValueChange: (Float) -> Unit,
    onDragStart: () -> Unit = {},
    testTag: String,
    modifier: Modifier = Modifier
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var dragMsTotal by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .size(48.dp)
            .testTag(testTag)
            .semantics {
                contentDescription = label
                stateDescription = "${valueMs.toInt()} milliseconds"
            }
            .pointerInput(maxMs, msPerPixel) {
                detectDragGestures(
                    onDragStart = {
                        onDragStart()
                        dragMsTotal = valueMs.coerceIn(0f, maxMs)
                        dragValue = dragMsTotal
                    },
                    onDragEnd = { dragValue = null },
                    onDragCancel = { dragValue = null },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragMsTotal = (dragMsTotal + dragAmount.x * msPerPixel).coerceIn(0f, maxMs)
                        dragValue = dragMsTotal
                        onValueChange(dragMsTotal)
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .background(accentColor)
        )
        dragValue?.let { ms ->
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(StudioBackground)
                    .border(1.dp, accentColor, RoundedCornerShape(Radius.sm))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${ms.toInt()} ms",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RangeHandle(
    centerX: androidx.compose.ui.unit.Dp,
    viewportWidth: androidx.compose.ui.unit.Dp,
    accentColor: Color,
    label: String,
    testTag: String,
    hapticsEnabled: Boolean = true,
    onDrag: (Float) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .offset(
                x = (centerX - 24.dp).coerceIn(
                    0.dp,
                    (viewportWidth - 48.dp).coerceAtLeast(0.dp)
                )
            )
            .width(48.dp)
            .fillMaxHeight()
            .testTag(testTag)
            .semantics { contentDescription = label }
            .pointerInput(label) {
                detectDragGestures(
                    onDragStart = { haptics.studioConfirm(hapticsEnabled) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(accentColor)
        )
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
                .widthIn(max = 560.dp)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                // Modal Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.ui_clip_inspector_fades), color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${clip.name} • Track ${track.index + 1}", color = TextSecondary, fontSize = 11.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close clip inspector", tint = TextSecondary)
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
                Text(stringResource(R.string.ui_fade_curve_algorithm), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSel) NeonCyan.copy(alpha = 0.2f) else StudioCard)
                                .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(Radius.md))
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    selectedCurve = curve
                                    onSetFadeIn(fadeInMs, curve)
                                    onSetFadeOut(fadeOutMs, curve)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = curve.displayName.substringBefore(" "),
                                color = if (isSel) NeonCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Fast Chorus Pasting Actions
                Text(stringResource(R.string.ui_chorus_adapting), color = StudioPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onPasteChorusSection(4) },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioPurple),
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ui_chorus_4_bars), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onPasteChorusDouble() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ui_doubler_layer), fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onAdaptChorusLoop() },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ui_4_bar_loop), fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
