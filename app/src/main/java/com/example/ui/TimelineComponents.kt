package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
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
import com.example.audio.model.EditMode
import com.example.audio.model.FadeCurve
import com.example.audio.model.GridResolution
import com.example.audio.model.TimelineTool
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
import com.example.ui.theme.OnAccent
import com.example.ui.theme.OverlayWhite
import com.example.ui.theme.Scrim
import com.example.ui.theme.Transparent

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
    editSelectionRange: LongRange? = null,
    onSelect: () -> Unit,
    onToggleClip: (String) -> Unit = {},
    onMoveClip: (String, Long, Long) -> Unit = { _, _, _ -> },
    onTrimClip: (String, Boolean, Long, Long) -> Unit = { _, _, _, _ -> },
    onEditSelectionDrag: (Long, Long) -> Unit = { _, _ -> },
    onEditSelectionCommit: () -> Unit = {},
    onClipDragStart: () -> Unit = {},
    /** Called with the clip id when a drag moved a clip, null for other drags. */
    onClipDragEnd: (String?) -> Unit = {},
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

                // Pro Tools style edit selection band for this track: the
                // range that trim / separate / heal / delete act on.
                if (editSelectionRange != null) {
                    val selStartX = (editSelectionRange.first.toDouble() / total * laneWidth).toFloat()
                    val selEndX = (editSelectionRange.last.toDouble() / total * laneWidth).toFloat()
                    drawRect(
                        color = ElectricLime.copy(alpha = 0.18f),
                        topLeft = Offset(selStartX, 0f),
                        size = Size((selEndX - selStartX).coerceAtLeast(1f), laneHeight)
                    )
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
                            color = OverlayWhite,
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
                                color = OverlayWhite.copy(alpha = 0.85f),
                                start = Offset(px, top),
                                end = Offset(px, bot),
                                strokeWidth = 1.2f
                            )
                        }
                    } else {
                        drawLine(
                            color = OverlayWhite.copy(alpha = 0.3f),
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
                        drawPath(fadePath, color = Scrim.copy(alpha = 0.45f))
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
                        drawPath(fadeOutPath, color = Scrim.copy(alpha = 0.45f))
                        drawLine(
                            color = StudioAmber,
                            start = Offset(clipX + clipW, laneHeight - 2f),
                            end = Offset(clipX + clipW - fadeOutWidth, 2f),
                            strokeWidth = 2f
                        )
                    }
                }

                // Selection edges stay crisp above the clip boxes.
                if (editSelectionRange != null) {
                    val selStartX = (editSelectionRange.first.toDouble() / total * laneWidth).toFloat()
                    val selEndX = (editSelectionRange.last.toDouble() / total * laneWidth).toFloat()
                    drawLine(ElectricLime, Offset(selStartX, 0f), Offset(selStartX, laneHeight), 2f)
                    drawLine(ElectricLime, Offset(selEndX, 0f), Offset(selEndX, laneHeight), 2f)
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
                // Where the finger went down. A Smart tool decision has to come
                // from the press point, not from wherever the drag finally
                // crossed the touch slop, or a long body drag would trim.
                val pressX = remember(clip.id) { mutableFloatStateOf(-1f) }
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
                        .pointerInput(clip.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.firstOrNull { it.changedToDown() }?.let { press ->
                                        pressX.floatValue = press.position.x
                                    }
                                }
                            }
                        }
                        .pointerInput(clip.id, contentWidthPx, settings.timelineTool, settings.editMode) {
                            // The active tool decides what this drag means before it
                            // starts, exactly like a Pro Tools toolbar.
                            var dragKind = ClipDragKind.MOVE
                            var dragFrame = clip.timelineStartFrame
                            var dragEdgeFrame = clip.timelineStartFrame
                            var selectionAnchor = clip.timelineStartFrame
                            var selectionPointerX = 0f
                            val edgeZonePx = EDGE_ZONE_WIDTH.toPx()
                            // Snap window in frames, so the magnet feels the same at
                            // every zoom level (12 dp of screen travel).
                            val snapTolerance = (12f * framesPerPixel).toLong().coerceAtLeast(1L)

                            fun resolveDragKind(startX: Float, width: Int): ClipDragKind =
                                when (settings.timelineTool) {
                                    TimelineTool.SELECTOR -> ClipDragKind.SELECT
                                    TimelineTool.GRABBER -> ClipDragKind.MOVE
                                    TimelineTool.TRIM -> if (startX <= edgeZonePx) {
                                        ClipDragKind.TRIM_START
                                    } else {
                                        ClipDragKind.TRIM_END
                                    }
                                    TimelineTool.SMART -> when {
                                        startX <= edgeZonePx -> ClipDragKind.TRIM_START
                                        startX >= width - edgeZonePx -> ClipDragKind.TRIM_END
                                        else -> ClipDragKind.MOVE
                                    }
                                }

                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val pressPoint = if (pressX.floatValue >= 0f) {
                                        pressX.floatValue
                                    } else {
                                        startOffset.x
                                    }
                                    dragKind = resolveDragKind(pressPoint, size.width)
                                    dragFrame = clip.timelineStartFrame
                                    dragEdgeFrame = clip.timelineStartFrame
                                    selectionPointerX = pressPoint
                                    selectionAnchor = clip.timelineStartFrame + (
                                        (startOffset.x / size.width.coerceAtLeast(1)) * clip.lengthFrames
                                        ).toLong()
                                    if (dragKind != ClipDragKind.SELECT) {
                                        haptics.studioConfirm(settings.enableHapticFeedback)
                                    }
                                    onClipDragStart()
                                },
                                onDragCancel = { onClipDragEnd(null) },
                                onDragEnd = {
                                    if (dragKind == ClipDragKind.SELECT) onEditSelectionCommit()
                                    onClipDragEnd(if (dragKind == ClipDragKind.MOVE) clip.id else null)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    when (dragKind) {
                                        ClipDragKind.MOVE -> {
                                            dragFrame = (dragFrame + dragAmount.x * framesPerPixel).toLong()
                                                .coerceAtLeast(0L)
                                            onMoveClip(clip.id, dragFrame, snapTolerance)
                                        }
                                        ClipDragKind.TRIM_START -> {
                                            dragEdgeFrame = (dragEdgeFrame + dragAmount.x * framesPerPixel).toLong()
                                            onTrimClip(clip.id, true, dragEdgeFrame, snapTolerance)
                                        }
                                        ClipDragKind.TRIM_END -> {
                                            dragEdgeFrame = (dragEdgeFrame + dragAmount.x * framesPerPixel).toLong()
                                            onTrimClip(clip.id, false, dragEdgeFrame, snapTolerance)
                                        }
                                        ClipDragKind.SELECT -> {
                                            val width = size.width.coerceAtLeast(1)
                                            selectionPointerX = (selectionPointerX + dragAmount.x)
                                                .coerceIn(0f, width.toFloat())
                                            val pointerFrame = clip.timelineStartFrame +
                                                ((selectionPointerX / width) * clip.lengthFrames).toLong()
                                            onEditSelectionDrag(
                                                minOf(selectionAnchor, pointerFrame),
                                                maxOf(selectionAnchor, pointerFrame)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                )
            }

            // Fade handles stay on the selected clip(s). Each target is a
            // 48dp wide strip along the top of the clip, so the edge below it
            // is still free for trimming rather than starting a fade.
            (if (selectedClips.isNotEmpty()) selectedClips else listOfNotNull(selectedClip)).forEach { clip ->
                val clipStartFrac = (clip.timelineStartFrame.toFloat() / totalFrames).coerceIn(0f, 1f)
                val clipLenFrac = (clip.lengthFrames.toFloat() / totalFrames).coerceIn(0.01f, 1f)
                val clipX = contentWidth * clipStartFrac
                val clipW = contentWidth * clipLenFrac
                val durationMs = (clip.lengthFrames / 48f).coerceAtLeast(1f)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val msPerPx = durationMs / with(density) { clipW.toPx() }.coerceAtLeast(1f)

                FadeDragHandle(
                    label = stringResource(R.string.label_fade_in),
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
                    label = stringResource(R.string.label_fade_out),
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
            .width(48.dp)
            .height(FADE_GRIP_HEIGHT)
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
internal fun RangeHandle(
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

