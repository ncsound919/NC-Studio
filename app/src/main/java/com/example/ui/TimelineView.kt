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
import com.example.audio.model.StripSilenceMode
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
import com.overlay.audio.sample.SampleEdit
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
    onSetTimelineTool: (TimelineTool) -> Unit = {},
    onSetEditMode: (EditMode) -> Unit = {},
    onTrimClipToFrame: (Int, String, Boolean, Long, Long) -> Unit = { _, _, _, _, _ -> },
    onEditSelectionChange: (Int, Long, Long) -> Unit = { _, _, _ -> },
    onClearEditSelection: () -> Unit = {},
    onBeginClipGesture: (Int, String) -> Unit = { _, _ -> },
    onEndClipGesture: () -> Unit = {},
    onHealClip: (Int, String) -> Unit = { _, _ -> },
    onCrossfadeClips: () -> Unit = {},
    onSeparateAtSelection: () -> Unit = {},
    onClearSelectedClips: () -> Unit = {},
    onSpotClipAtFrame: (Int, String, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onSetLoopRange: (Long, Long) -> Unit = { _, _ -> },
    onSetPunchRange: (Long, Long) -> Unit = { _, _ -> },
    onToggleClipSelection: (Int, String) -> Unit = { _, _ -> },
    onClearClipSelection: () -> Unit = {},
    onMoveClip: (Int, String, Long, Long) -> Unit = { _, _, _, _ -> },
    onNudgeClip: (Int, String, Boolean) -> Unit = { _, _, _ -> },
    onSetClipGain: (Int, String, Float) -> Unit = { _, _, _ -> },
    onSetClipFadeIn: (Int, String, Float, FadeCurve) -> Unit = { _, _, _, _ -> },
    onSetClipFadeOut: (Int, String, Float, FadeCurve) -> Unit = { _, _, _, _ -> },
    onPasteChorusSection: (Int, String, Int) -> Unit = { _, _, _ -> },
    onPasteChorusDouble: (Int, String, Int) -> Unit = { _, _, _ -> },
    onAdaptChorusLoop: (Int, String, Int) -> Unit = { _, _, _ -> },
    onStripSilence: (SampleEdit.StripSettings, StripSilenceMode) -> Unit = { _, _ -> },
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
    // Spot request: only the track and clip are remembered, so the dialog always
    // reads the clip's current position out of the session.
    var spotRequest by remember { mutableStateOf<SpotRequest?>(null) }
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
                            text = if (zoom <= 1.01f) "FIT" else "x${String.format(java.util.Locale.US, "%.1f", zoom)}",
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

        // Pro Tools style tool rail: the tool decides what a drag on a clip means
        // before the gesture starts, and the edit mode decides where edits land.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .horizontalScroll(rememberScrollState())
                .testTag("timeline_tool_rail"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TimelineTool.values().forEach { tool ->
                val active = settings.timelineTool == tool
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(if (active) NeonCyan.copy(alpha = 0.22f) else StudioCard)
                        .border(1.dp, if (active) NeonCyan else StudioCardBorder, RoundedCornerShape(Radius.md))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            haptics.studioTick(settings.enableHapticFeedback)
                            onSetTimelineTool(tool)
                        }
                        .semantics {
                            contentDescription = "${tool.displayName} tool"
                            stateDescription = if (active) "Active. ${tool.description}" else tool.description
                        }
                        .padding(horizontal = 10.dp)
                        .testTag("timeline_tool_${tool.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tool.displayName.uppercase(),
                        color = if (active) NeonCyan else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(StudioCardBorder)
            )

            EditMode.values().forEach { mode ->
                val active = settings.editMode == mode
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(if (active) StudioAmber.copy(alpha = 0.2f) else StudioCard)
                        .border(1.dp, if (active) StudioAmber else StudioCardBorder, RoundedCornerShape(Radius.md))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            haptics.studioTick(settings.enableHapticFeedback)
                            onSetEditMode(mode)
                        }
                        .semantics {
                            contentDescription = "${mode.displayName} edit mode"
                            stateDescription = if (active) "Active. ${mode.description}" else mode.description
                        }
                        .padding(horizontal = 10.dp)
                        .testTag("edit_mode_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.displayName.uppercase(),
                        color = if (active) StudioAmber else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(StudioCardBorder)
            )

            // Pro Tools edit commands, also on keyboard: B separates at the
            // playhead, Ctrl+E at the selection, Ctrl+H heals, Ctrl+F crossfades.
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (uiState.editSelection != null) NeonCyan.copy(alpha = 0.18f) else StudioCard)
                    .border(
                        1.dp,
                        if (uiState.editSelection != null) NeonCyan else StudioCardBorder,
                        RoundedCornerShape(Radius.md)
                    )
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onSeparateAtSelection() }
                    .semantics { contentDescription = "Separate clips at the selection" }
                    .padding(horizontal = 10.dp)
                    .testTag("action_separate_at_selection"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.timeline_sep),
                    color = if (uiState.editSelection != null) NeonCyan else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        firstSelectedClip?.let { onHealClip(selectedTrack?.index ?: 0, it.id) }
                    }
                    .semantics { contentDescription = "Heal separation" }
                    .padding(horizontal = 10.dp)
                    .testTag("action_heal_clip"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.timeline_heal),
                    color = if (firstSelectedClip != null) TextPrimary else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onCrossfadeClips() }
                    .semantics { contentDescription = "Crossfade selected clips" }
                    .padding(horizontal = 10.dp)
                    .testTag("action_crossfade_clips"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.timeline_crossfade),
                    color = if (selectedClips.size >= 2) TextPrimary else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClearSelectedClips() }
                    .semantics { contentDescription = "Clear selected clips" }
                    .padding(horizontal = 10.dp)
                    .testTag("action_clear_clips"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.timeline_clear),
                    color = if (selectedClips.isNotEmpty()) TextPrimary else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
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

            // One clip button system: every clip operation, including Strip
            // Silence, lives in ClipActionsSheet. The toolbar exposes one entry
            // point so an edit never depends on finding a particular icon.
            StudioTooltip("Clip actions: strip silence, fades, split, duplicate, nudge") {
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(if (firstSelectedClip != null) StudioPurple.copy(alpha = 0.25f) else StudioCard)
                        .border(
                            1.dp,
                            if (firstSelectedClip != null) StudioPurple else StudioCardBorder,
                            RoundedCornerShape(Radius.md)
                        )
                        .clickable(
                            enabled = firstSelectedClip != null,
                            role = androidx.compose.ui.semantics.Role.Button
                        ) { showClipAdjustModal = true }
                        .padding(horizontal = 12.dp)
                        .testTag("open_clip_actions_sheet"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = if (firstSelectedClip != null) StudioPurple else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "CLIP",
                            color = if (firstSelectedClip != null) StudioPurple else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        val summary = when {
                            selectedClips.size > 1 -> "${selectedClips.size} selected"
                            firstSelectedClip != null -> firstSelectedClip.name
                            else -> "no clip"
                        }
                        Text(
                            text = summary,
                            color = TextMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp)
                        )
                    }
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
                        label = stringResource(R.string.label_loop_start),
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
                        label = stringResource(R.string.label_loop_end),
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
                        label = stringResource(R.string.label_punch_in_point),
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
                        label = stringResource(R.string.label_punch_out_point),
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

                        // Pro Tools edit selection: the range operations act on.
                        val editSelectionRange = uiState.editSelection?.let { it.startFrame..it.endFrame }
                        editSelectionRange?.let { range ->
                            val startX = frameToX(range.start)
                            val endX = frameToX(range.endInclusive)
                            drawRect(
                                color = ElectricLime.copy(alpha = 0.10f),
                                topLeft = Offset(startX, 0f),
                                size = Size((endX - startX).coerceAtLeast(1f), size.height)
                            )
                            drawLine(
                                color = ElectricLime,
                                start = Offset(startX, 0f),
                                end = Offset(startX, size.height),
                                strokeWidth = 2f
                            )
                            drawLine(
                                color = ElectricLime,
                                start = Offset(endX, 0f),
                                end = Offset(endX, size.height),
                                strokeWidth = 2f
                            )
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
                        editSelectionRange = uiState.editSelection
                            ?.takeIf { it.trackIndex == index }
                            ?.let { it.startFrame..it.endFrame },
                        onSelect = { onSelectTrack(index) },
                        onToggleClip = { clipId -> onToggleClipSelection(index, clipId) },
                        onMoveClip = { clipId, frame, tolerance ->
                            onBeginClipGesture(index, "Move clip")
                            onMoveClip(index, clipId, frame, tolerance)
                        },
                        onTrimClip = { clipId, isStartEdge, frame, tolerance ->
                            onBeginClipGesture(index, if (isStartEdge) "Trim clip start" else "Trim clip end")
                            onTrimClipToFrame(index, clipId, isStartEdge, frame, tolerance)
                        },
                        onEditSelectionDrag = { start, end -> onEditSelectionChange(index, start, end) },
                        onEditSelectionCommit = { onEndClipGesture(); onSelectTrack(index) },
                        onClipDragStart = { snapFlashFrame = null },
                        onClipDragEnd = { movedClipId ->
                            onEndClipGesture()
                            markEditLanded()
                            // Spot mode asks for the destination once the drop
                            // settles, the way the Pro Tools spot dialog does.
                            if (movedClipId != null && settings.editMode == EditMode.SPOT) {
                                spotRequest = SpotRequest(index, movedClipId)
                            }
                        },
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

    // Pro Tools Spot dialog: opened when a drag settles in Spot edit mode.
    val spotClip = spotRequest?.let { request ->
        session.tracks.getOrNull(request.trackIndex)
            ?.clips
            ?.firstOrNull { it.id == request.clipId }
    }
    if (spotClip != null) {
        SpotClipDialog(
            clipName = spotClip.name,
            startFrame = spotClip.timelineStartFrame,
            clipLengthFrames = spotClip.lengthFrames,
            sampleRate = session.sampleRate,
            onDismiss = { spotRequest = null },
            onSpot = { frame, placeByEnd ->
                val request = spotRequest
                if (request != null) {
                    onSpotClipAtFrame(request.trackIndex, request.clipId, frame, placeByEnd)
                    markEditLanded()
                }
                spotRequest = null
            }
        )
    }

    // One clip sheet: fades, chorus adapting, edit commands and Strip Silence.
    if (showClipAdjustModal && firstSelectedClip != null) {
        val trackForModal = selectedTrack ?: return@TimelineView
        val clipForModal = firstSelectedClip
        ClipActionsSheet(
            clip = clipForModal,
            track = trackForModal,
            selectedCount = selectedClips.size,
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
            },
            onSeparateAtPlayhead = {
                onSplitClipAtPlayhead(trackForModal.index)
                markEditLanded()
            },
            onSeparateAtSelection = onSeparateAtSelection,
            onHeal = { onHealClip(trackForModal.index, clipForModal.id) },
            onCrossfade = onCrossfadeClips,
            onDuplicate = {
                onDuplicateClip(trackForModal.index, clipForModal.id)
                markEditLanded()
            },
            onNudgeBack = { onNudgeClip(trackForModal.index, clipForModal.id, false) },
            onNudgeForward = { onNudgeClip(trackForModal.index, clipForModal.id, true) },
            onDeleteClips = onClearSelectedClips,
            onStripSilence = onStripSilence,
        )
    }
}

/** What the pointer is doing on a clip, decided by the active timeline tool. */
internal enum class ClipDragKind { MOVE, TRIM_START, TRIM_END, SELECT }

/** Width of the trim/fade zone at each end of a clip. */
/** A spot that still needs a destination time from the engineer. */
private data class SpotRequest(val trackIndex: Int, val clipId: String)

internal val EDGE_ZONE_WIDTH = 18.dp
/** Height of a fade grip strip: leaves the rest of the lane height trimmable. */
internal val FADE_GRIP_HEIGHT = 24.dp
