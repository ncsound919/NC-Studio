package com.ncstudio.daw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ScrollState
import com.ncstudio.daw.R
import com.ncstudio.daw.audio.model.AudioClip
import com.ncstudio.daw.audio.model.TakeLane
import com.ncstudio.daw.audio.model.TrackData
import com.ncstudio.daw.audio.model.timelineEndFrame
import com.ncstudio.daw.ui.theme.ElectricLime
import com.ncstudio.daw.ui.theme.NeonCyan
import com.ncstudio.daw.ui.theme.PanelInset
import com.ncstudio.daw.ui.theme.Radius
import com.ncstudio.daw.ui.theme.StudioSurface
import com.ncstudio.daw.ui.theme.TextMuted
import com.ncstudio.daw.ui.theme.TextPrimary

/** Height of one take row. Slim: 16 tracks of takes must still fit on screen. */
internal val TAKE_ROW_HEIGHT = 30.dp

/**
 * Take lanes under a track: every recorded alternate is visible against the same
 * timeline, aligned with the clip area above it. Tapping a row makes that take
 * audible; tapping a clip in another take comps that whole phrase in; dragging
 * across a row comps just the dragged range.
 *
 * Nothing renders for a track that never overdubbed, so a normal session keeps
 * its full timeline height.
 */
@Composable
fun TrackTakeLanes(
    track: TrackData,
    totalFrames: Long,
    contentZoom: Float = 1f,
    scrollState: ScrollState = rememberScrollState(),
    onSelectTake: (String) -> Unit = {},
    onCompTake: (String, Long, Long) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    if (track.takeLanes.size < 2) return
    val total = totalFrames.coerceAtLeast(1L)
    Column(modifier = modifier.fillMaxWidth().testTag("take_lanes_${track.index}")) {
        track.takeLanes.forEach { lane ->
            TakeRow(
                track = track,
                lane = lane,
                totalFrames = total,
                contentZoom = contentZoom,
                scrollState = scrollState,
                isActive = lane.id == track.activeTakeId,
                onSelectTake = onSelectTake,
                onCompTake = onCompTake
            )
        }
    }
}

@Composable
private fun TakeRow(
    track: TrackData,
    lane: TakeLane,
    totalFrames: Long,
    contentZoom: Float,
    scrollState: ScrollState,
    isActive: Boolean,
    onSelectTake: (String) -> Unit,
    onCompTake: (String, Long, Long) -> Unit
) {
    val density = LocalDensity.current
    var dragFrom by remember(lane.id) { mutableStateOf<Long?>(null) }
    var dragTo by remember(lane.id) { mutableStateOf<Long?>(null) }
    val laneColor = if (lane.colorHex != 0L) Color(lane.colorHex) else Color(track.colorHex)
    val selectCd = stringResource(R.string.take_select_cd, lane.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TAKE_ROW_HEIGHT)
            .background(if (isActive) PanelInset else StudioSurface)
            .testTag("take_row_${lane.id}")
    ) {
        Row(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .clickable { onSelectTake(lane.id) }
                .semantics {
                    contentDescription = selectCd
                    stateDescription = if (isActive) "Audible take" else "Alternate take"
                }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .background(if (isActive) NeonCyan else laneColor.copy(alpha = 0.4f))
            )
            Text(
                text = lane.name,
                color = if (isActive) TextPrimary else TextMuted,
                fontSize = 10.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (lane.rating > 0) {
                Text(
                    text = stringResource(R.string.take_stars, lane.rating),
                    color = ElectricLime,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // The scroll lives on an inner box: applying it to this one would
            // measure the lane against an infinite width and push every clip
            // outside the screen.
            val contentWidth = maxWidth * contentZoom
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(scrollState)) {
              Box(
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight()
                    .pointerInput(lane.id, totalFrames, contentZoom) {
                        val laneWidth = size.width.toFloat().coerceAtLeast(1f)
                        fun frameAt(x: Float): Long =
                            ((x / laneWidth).coerceIn(0f, 1f) * totalFrames).toLong()

                        detectDragGestures(
                            onDragStart = { offset ->
                                val frame = frameAt(offset.x)
                                dragFrom = frame
                                dragTo = frame
                            },
                            onDragEnd = {
                                val from = dragFrom
                                val to = dragTo
                                dragFrom = null
                                dragTo = null
                                if (from != null && to != null && to - from > 0L && !isActive) {
                                    onCompTake(lane.id, from, to)
                                }
                            },
                            onDragCancel = {
                                dragFrom = null
                                dragTo = null
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragTo = frameAt(change.position.x)
                            }
                        )
                    }
              ) {
                for (clip in lane.clips) {
                    TakeClipBlock(
                        clip = clip,
                        lane = lane,
                        totalFrames = totalFrames,
                        contentWidthDp = contentWidth,
                        color = laneColor,
                        isActive = isActive,
                        onComp = { onCompTake(lane.id, clip.timelineStartFrame, clip.timelineEndFrame) }
                    )
                }

                val from = dragFrom
                val to = dragTo
                if (from != null && to != null && to > from) {
                    val left = contentWidth * (from.toFloat() / totalFrames)
                    val width = (contentWidth * ((to - from).toFloat() / totalFrames)).coerceAtLeast(0.5.dp)
                    Box(
                        modifier = Modifier
                            .offset(x = left)
                            .width(width)
                            .fillMaxHeight()
                            .background(ElectricLime.copy(alpha = 0.28f))
                            .testTag("take_drag_range_${lane.id}")
                    )
                }
              }
            }
        }
    }
}

@Composable
private fun TakeClipBlock(
    clip: AudioClip,
    lane: TakeLane,
    totalFrames: Long,
    contentWidthDp: androidx.compose.ui.unit.Dp,
    color: Color,
    isActive: Boolean,
    onComp: () -> Unit
) {
    val left = contentWidthDp * (clip.timelineStartFrame.toFloat() / totalFrames)
    val width = (contentWidthDp * (clip.lengthFrames.toFloat() / totalFrames))
        .coerceAtLeast(1.dp)
    val compCd = stringResource(R.string.take_lane_comp_clip, clip.name, lane.name)
    Box(
        modifier = Modifier
            .offset(x = left)
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(Radius.sm))
            .background(color.copy(alpha = if (isActive) 0.55f else 0.28f))
            .clickable(enabled = !isActive) { onComp() }
            .semantics {
                contentDescription = compCd
                stateDescription = if (isActive) "Audible" else "Tap to comp this clip in"
            }
            .testTag("take_clip_${clip.id}")
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (width > 44.dp) {
            Text(
                text = clip.name,
                color = TextPrimary.copy(alpha = if (isActive) 1f else 0.75f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
