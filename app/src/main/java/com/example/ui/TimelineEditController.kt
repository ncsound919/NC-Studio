package com.example.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.audio.engine.SnapEngine
import com.example.audio.history.SessionCommand
import com.example.audio.history.UndoManager
import com.example.audio.model.AudioClip
import com.example.audio.model.DawSettings
import com.example.audio.model.EditMode
import com.example.audio.model.EditSelection
import com.example.audio.model.FadeCurve
import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import com.example.audio.model.TimelineTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.max

/**
 * Pro Tools style timeline editing: tools, edit modes, selection, and every
 * non-destructive clip operation (separate, heal, trim, move, crossfade,
 * duplicate, nudge).
 *
 * The controller mutates the shared session flow the same way the ViewModel
 * did, so behaviour is unchanged; the ViewModel exposes delegating methods and
 * keeps owning undo, journal writes and the audible track rebuild.
 */
class TimelineEditController(
    private val sampleRate: Int,
    private val session: MutableStateFlow<ProjectSession>,
    private val uiState: MutableStateFlow<EngineUiState>,
    private val settings: MutableStateFlow<DawSettings>,
    private val undoManager: UndoManager,
    private val prefs: SharedPreferences,
    private val requestJournalWrite: (ProjectSession) -> Unit,
    private val scheduleRebuildTrack: (Int) -> Unit,
    private val updateTrackChorus: (Int, com.example.audio.model.ChorusConfig) -> Unit,
    private val publishStatus: (String, StatusKind, StatusAction?) -> Unit,
) {

    private fun setStatus(
        message: String,
        kind: StatusKind = StatusKind.INFO,
        action: StatusAction? = null,
    ) {
        publishStatus(message, kind, action)
    }

    // Clip Editing (Nondestructive)
    fun splitClipAtPlayhead(trackIndex: Int) {
        val separated = separateClipsAtFrame(uiState.value.playheadFrame, trackIndex)
        if (separated == 0) {
            setStatus("Nothing to separate at the playhead on track ${trackIndex + 1}.", StatusKind.INFO)
        } else {
            setStatus("Separated $separated clip(s) at the playhead.", StatusKind.SUCCESS, StatusAction("Undo"))
        }
    }

    // ------------------------------------------------------------------
    // Pro Tools style timeline tools, edit modes and clip operations
    // ------------------------------------------------------------------

    /** A drag or trim that undoes as one entry instead of one per pointer event. */
    private data class ClipGesture(
        val trackIndex: Int,
        val beforeClips: List<AudioClip>,
        val description: String
    )

    private var clipGesture: ClipGesture? = null

    /** Opens a gesture so the whole drag or trim undoes as a single edit. */
    fun beginClipGesture(trackIndex: Int, description: String) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        if (clipGesture?.trackIndex == trackIndex) return
        clipGesture = ClipGesture(trackIndex, track.clips, description)
    }

    /** Closes the gesture, records one undo entry, and tidies any overlap. */
    fun endClipGesture() {
        val gesture = clipGesture ?: return
        clipGesture = null
        val track = session.value.tracks.getOrNull(gesture.trackIndex) ?: return
        if (track.clips != gesture.beforeClips) {
            undoManager.pushAction(
                SessionCommand.EditClips(
                    gesture.description,
                    gesture.trackIndex,
                    gesture.beforeClips,
                    track.clips
                )
            )
            // Spot mode places a clip at the exact drop point and reports where
            // it landed, so the time can be read back and corrected by hand.
            if (settings.value.editMode == EditMode.SPOT) {
                val before = gesture.beforeClips.associateBy { it.id }
                val stamped = track.clips.firstOrNull { clip ->
                    before[clip.id]?.timelineStartFrame != clip.timelineStartFrame
                }
                if (stamped != null) {
                    setStatus(
                        "Spot: ${stamped.name} at ${Timecode.format(stamped.timelineStartFrame, sampleRate)}. " +
                            "Type a time to place it exactly.",
                        StatusKind.INFO
                    )
                }
            }
        }
        applyAutoCrossfades(gesture.trackIndex)
    }

    /**
     * Pro Tools "Spot": places a clip at an exact time the engineer types into
     * the Spot dialog. Nothing is snapped here: a spot is a precise placement,
     * and [placeByEnd] aligns the clip's out point instead of its start.
     */
    fun spotClipAtFrame(
        trackIndex: Int,
        clipId: String,
        frame: Long,
        placeByEnd: Boolean = false
    ): Boolean {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return false
        val clip = track.clips.find { it.id == clipId } ?: return false
        val target = if (placeByEnd) {
            (frame - clip.lengthFrames).coerceAtLeast(0L)
        } else {
            frame.coerceAtLeast(0L)
        }
        if (target == clip.timelineStartFrame) {
            setStatus(
                "${clip.name} is already at ${Timecode.format(target, sampleRate)}.",
                StatusKind.INFO
            )
            return false
        }

        val moved = track.clips.map {
            if (it.id == clipId) it.copy(timelineStartFrame = target) else it
        }
        pushClipEdit(
            trackIndex,
            "Spot ${clip.name} to ${Timecode.format(target, sampleRate)}",
            track.clips,
            moved
        )
        applyClipsToTrack(trackIndex, moved)
        setStatus(
            "Spot: ${clip.name} at ${Timecode.format(target, sampleRate)}.",
            StatusKind.SUCCESS,
            StatusAction("Undo")
        )
        return true
    }

    /** One undo entry per discrete edit, or none while a gesture owns the edit. */
    private fun pushClipEdit(
        trackIndex: Int,
        description: String,
        beforeClips: List<AudioClip>,
        afterClips: List<AudioClip>
    ) {
        if (clipGesture?.trackIndex == trackIndex) return
        undoManager.pushAction(
            SessionCommand.EditClips(description, trackIndex, beforeClips, afterClips)
        )
    }

    fun setTimelineTool(tool: TimelineTool) {
        settings.update { it.copy(timelineTool = tool) }
        prefs.edit { putString(TIMELINE_TOOL_PREF, tool.name) }
    }

    fun setEditMode(mode: EditMode) {
        settings.update { it.copy(editMode = mode) }
        prefs.edit { putString(EDIT_MODE_PREF, mode.name) }
        setStatus("${mode.displayName} mode: ${mode.description}", StatusKind.INFO)
    }

    fun setEditSelection(trackIndex: Int, startFrame: Long, endFrame: Long) {
        val total = session.value.totalLengthFrames.coerceAtLeast(1L)
        val start = startFrame.coerceIn(0L, total - 1L)
        val end = endFrame.coerceIn(start + 1L, total)
        uiState.update { it.copy(editSelection = EditSelection(trackIndex, start, end)) }
    }

    fun clearEditSelection() {
        if (uiState.value.editSelection == null) return
        uiState.update { it.copy(editSelection = null) }
    }

    /**
     * Finishes a selection drag: a stray drag that never grew past a grid step is
     * dropped, anything longer is announced so the engineer knows what B/E will act on.
     */
    fun commitEditSelection() {
        val selection = uiState.value.editSelection ?: return
        if (selection.lengthFrames < gridStepFrames(settings.value.gridResolution) / 2) {
            clearEditSelection()
            return
        }
        setStatus(
            "Selection: ${selection.lengthFrames / 48} ms on track ${selection.trackIndex + 1}.",
            StatusKind.INFO
        )
    }

    /** Heals the first selected clip, or the clip sitting under the playhead. */
    fun healSelectedClip(): Boolean {
        val trackIndex = uiState.value.selectedTrackIndex
        val track = session.value.tracks.getOrNull(trackIndex) ?: return false
        val playhead = uiState.value.playheadFrame
        val target = track.clips.firstOrNull { it.id in uiState.value.selectedClipIds }
            ?: track.clips.firstOrNull {
                playhead in it.timelineStartFrame until (it.timelineStartFrame + it.lengthFrames)
            }
            ?: run {
                setStatus("Put the playhead on a clip, or select one, then Heal.", StatusKind.ERROR)
                return false
            }
        return healSeparation(trackIndex, target.id)
    }

    /**
     * Pro Tools "Separate Clip at Playhead": cuts every clip that spans the
     * playhead into two clips that still share one source region, so
     * [healSeparation] can put them back together.
     */
    fun separateAtPlayhead(): Int {
        val separated = separateClipsAtFrame(
            uiState.value.playheadFrame,
            uiState.value.selectedTrackIndex
        )
        setStatus(
            message = if (separated == 0) {
                "Nothing to separate at the playhead."
            } else {
                "Separated $separated clip(s) at the playhead."
            },
            kind = if (separated == 0) StatusKind.INFO else StatusKind.SUCCESS,
            action = if (separated == 0) null else StatusAction("Undo")
        )
        return separated
    }

    /** Pro Tools "Separate Clip at Selection": cuts at both selection edges. */
    fun separateAtSelection(): Int {
        val selection = uiState.value.editSelection ?: return separateAtPlayhead()
        val cuts = separateClipsAtFrame(selection.startFrame, selection.trackIndex) +
            separateClipsAtFrame(selection.endFrame, selection.trackIndex)
        setStatus(
            message = if (cuts == 0) {
                "Nothing to separate inside the selection."
            } else {
                "Separated $cuts clip edge(s) at the selection."
            },
            kind = if (cuts == 0) StatusKind.INFO else StatusKind.SUCCESS,
            action = if (cuts == 0) null else StatusAction("Undo")
        )
        return cuts
    }

    /**
     * Cuts every clip on [trackIndex] that spans [frame] at that frame. Returns
     * the number of clips that were separated.
     */
    fun separateClipsAtFrame(frame: Long, trackIndex: Int): Int {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return 0
        val beforeClips = track.clips
        val afterClips = mutableListOf<AudioClip>()
        var separated = 0
        for (clip in beforeClips) {
            val offset = frame - clip.timelineStartFrame
            if (offset <= MIN_CLIP_FRAMES || offset >= clip.lengthFrames - MIN_CLIP_FRAMES) {
                afterClips += clip
                continue
            }
            separated++
            afterClips += clip.copy(
                id = UUID.randomUUID().toString(),
                name = "${clip.name} (Part 1)",
                lengthFrames = offset,
                fadeOutFrames = 0L
            ).clampedFades()
            afterClips += clip.copy(
                id = UUID.randomUUID().toString(),
                name = "${clip.name} (Part 2)",
                sourceStartFrame = clip.sourceStartFrame + offset,
                timelineStartFrame = frame,
                lengthFrames = clip.lengthFrames - offset,
                fadeInFrames = 0L
            ).clampedFades()
        }
        if (separated == 0) return 0
        pushClipEdit(trackIndex, "Separate clip at frame $frame", beforeClips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
        return separated
    }

    /**
     * Pro Tools "Heal Separation": re-joins a clip with the neighbour it was
     * separated from, when both still reference one contiguous source region.
     */
    fun healSeparation(trackIndex: Int, clipId: String): Boolean {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return false
        val clip = track.clips.find { it.id == clipId } ?: return false
        val sameSource = track.clips.filter { it.sourcePath == clip.sourcePath && it.id != clip.id }
        val left = sameSource
            .filter { it.timelineStartFrame + it.lengthFrames == clip.timelineStartFrame }
            .filter { it.sourceStartFrame + it.lengthFrames == clip.sourceStartFrame }
            .maxByOrNull { it.timelineStartFrame }
        val right = sameSource
            .filter { it.timelineStartFrame == clip.timelineStartFrame + clip.lengthFrames }
            .filter { it.sourceStartFrame == clip.sourceStartFrame + clip.lengthFrames }
            .minByOrNull { it.timelineStartFrame }
        if (left == null && right == null) {
            setStatus("Nothing to heal: no contiguous clip from the same take.", StatusKind.ERROR)
            return false
        }

        val healed = clip.copy(
            id = left?.id ?: clip.id,
            name = clip.name.removeSuffix(" (Part 1)").removeSuffix(" (Part 2)"),
            timelineStartFrame = left?.timelineStartFrame ?: clip.timelineStartFrame,
            sourceStartFrame = left?.sourceStartFrame ?: clip.sourceStartFrame,
            lengthFrames = (left?.lengthFrames ?: 0L) + clip.lengthFrames + (right?.lengthFrames ?: 0L),
            fadeInFrames = left?.fadeInFrames ?: clip.fadeInFrames,
            fadeOutFrames = right?.fadeOutFrames ?: clip.fadeOutFrames
        ).clampedFades()
        val consumed = setOfNotNull(left?.id, right?.id, clip.id)
        val afterClips = track.clips.filterNot { it.id in consumed } + healed

        pushClipEdit(trackIndex, "Heal separation on ${healed.name}", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
        setStatus("Healed separation on ${healed.name}.", StatusKind.SUCCESS, StatusAction("Undo"))
        return true
    }

    /** Clears every selected clip on the selected track. */
    fun deleteSelectedClips(): Int {
        val trackIndex = uiState.value.selectedTrackIndex
        val track = session.value.tracks.getOrNull(trackIndex) ?: return 0
        val selected = uiState.value.selectedClipIds
        if (selected.isEmpty()) {
            setStatus("Tap clips first, then Clear removes them.", StatusKind.ERROR)
            return 0
        }
        val afterClips = track.clips.filterNot { it.id in selected }
        val removed = track.clips.size - afterClips.size
        if (removed == 0) return 0
        pushClipEdit(trackIndex, "Clear $removed clip(s)", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
        uiState.update { it.copy(selectedClipIds = emptySet(), editSelection = null) }
        setStatus("Cleared $removed clip(s).", StatusKind.SUCCESS, StatusAction("Undo"))
        return removed
    }

    /**
     * Pro Tools nudge: shifts every selected clip by one grid step, or one bar
     * when [byBar] is set, keeping the selection so nudges can be repeated.
     */
    fun nudgeSelection(forward: Boolean, byBar: Boolean = false): Int {
        val trackIndex = uiState.value.selectedTrackIndex
        val track = session.value.tracks.getOrNull(trackIndex) ?: return 0
        val selected = uiState.value.selectedClipIds
        if (selected.isEmpty()) {
            setStatus("Select clips to nudge them with the arrow keys.", StatusKind.ERROR)
            return 0
        }
        val step = if (byBar) {
            SnapEngine.framesPerStep(GridResolution.BAR, sampleRate, session.value.tempoBpm)
        } else {
            gridStepFrames(settings.value.gridResolution)
        }.coerceAtLeast(1L)
        val delta = if (forward) step else -step
        val afterClips = track.clips.map { clip ->
            if (clip.id in selected) {
                clip.copy(timelineStartFrame = (clip.timelineStartFrame + delta).coerceAtLeast(0L))
            } else clip
        }
        pushClipEdit(trackIndex, "Nudge ${selected.size} clip(s) by $step frames", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
        return selected.size
    }

    /**
     * Pro Tools "Create Crossfade": equal-power fade pair across the overlap of
     * the selected clips. Clips that only touch get the configured default fade
     * length applied inside their own boundaries, so no audio is invented.
     */
    fun crossfadeSelectedClips(): Boolean {
        val trackIndex = uiState.value.selectedTrackIndex
        val track = session.value.tracks.getOrNull(trackIndex) ?: return false
        val selected = track.clips
            .filter { it.id in uiState.value.selectedClipIds }
            .sortedBy { it.timelineStartFrame }
        if (selected.size < 2) {
            setStatus("Select two neighbouring clips to crossfade.", StatusKind.ERROR)
            return false
        }
        val first = selected[selected.size - 2]
        val second = selected[selected.size - 1]
        val overlap = (first.timelineStartFrame + first.lengthFrames) - second.timelineStartFrame
        val defaultFade = (settings.value.crossfadeMs * sampleRate / 1000f).toLong().coerceAtLeast(1L)
        val fadeFrames = if (overlap > 0L) overlap else defaultFade

        val afterClips = track.clips.map { clip ->
            when (clip.id) {
                first.id -> clip.copy(
                    fadeOutFrames = fadeFrames.coerceAtMost(clip.lengthFrames),
                    fadeOutCurve = FadeCurve.EQUAL_POWER
                )
                second.id -> clip.copy(
                    fadeInFrames = fadeFrames.coerceAtMost(clip.lengthFrames),
                    fadeInCurve = FadeCurve.EQUAL_POWER
                )
                else -> clip
            }
        }
        pushClipEdit(trackIndex, "Crossfade ${first.name} to ${second.name}", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
        setStatus(
            "Crossfaded ${first.name} into ${second.name} over ${fadeFrames / 48} ms.",
            StatusKind.SUCCESS,
            StatusAction("Undo")
        )
        return true
    }

    /**
     * Overlapping clips on one track simply sum today, which is both louder and
     * phasey. Every overlap gets an equal-power fade pair, so a drop that lands
     * on another clip becomes a real crossfade the way a Pro Tools edit does.
     */
    private fun applyAutoCrossfades(trackIndex: Int) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val ordered = track.clips.sortedBy { it.timelineStartFrame }
        if (ordered.size < 2) return
        var changed = false
        val updated = ordered.toMutableList()
        for (i in 0 until updated.size - 1) {
            val first = updated[i]
            val second = updated[i + 1]
            val overlap = (first.timelineStartFrame + first.lengthFrames) - second.timelineStartFrame
            if (overlap <= 0L) continue
            val fadeFrames = overlap.coerceAtMost(minOf(first.lengthFrames, second.lengthFrames))
            if (first.fadeOutFrames >= fadeFrames && second.fadeInFrames >= fadeFrames) continue
            changed = true
            updated[i] = first.copy(fadeOutFrames = fadeFrames, fadeOutCurve = FadeCurve.EQUAL_POWER)
            updated[i + 1] = second.copy(fadeInFrames = fadeFrames, fadeInCurve = FadeCurve.EQUAL_POWER)
        }
        if (!changed) return
        applyClipsToTrack(trackIndex, updated)
    }

    /**
     * Installs a batch of clips computed outside the controller (Strip Silence,
     * future AudioSuite-style processes) as one undo entry. The caller owns the
     * audio analysis; this owns the edit, journal write and audible rebuild.
     */
    fun applyClipBatch(trackIndex: Int, description: String, clips: List<AudioClip>) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        if (track.clips == clips) return
        pushClipEdit(trackIndex, description, track.clips, clips)
        applyClipsToTrack(trackIndex, clips)
    }

    /** Writes a clip list straight through: session, journal and audible rebuild. */
    private fun applyClipsToTrack(trackIndex: Int, clips: List<AudioClip>) {
        val newTracks = session.value.tracks.map {
            if (it.index == trackIndex) it.copy(clips = clips) else it
        }
        val furthestEnd = clips.maxOfOrNull { it.timelineStartFrame + it.lengthFrames } ?: 0L
        session.update {
            it.copy(
                tracks = newTracks,
                totalLengthFrames = max(it.totalLengthFrames, furthestEnd),
                revision = it.revision + 1
            )
        }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
    }

    /**
     * Trims the out point by exactly [trimFrames]. This is the "Trim 1 s" style
     * button path, where the caller already decided the amount, so it is not
     * snapped: only dragging an out point goes through [trimClipEndToFrame].
     */
    fun trimClipEnd(trackIndex: Int, clipId: String, trimFrames: Long) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val newLength = (clip.lengthFrames - trimFrames).coerceAtLeast(MIN_CLIP_FRAMES)
        if (newLength == clip.lengthFrames) return

        val updated = clip.copy(lengthFrames = newLength).clampedFades()
        val trimmed = track.clips.map { if (it.id == clipId) updated else it }
        val afterClips = if (settings.value.editMode == EditMode.SHUFFLE) {
            packShuffledFrom(trimmed, clipId)
        } else trimmed

        pushClipEdit(trackIndex, "Trim out point of ${clip.name}", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
    }

    /**
     * Pro Tools trim: drags the out point of a clip to [rawEndFrame]. The content
     * stays put, the tail is uncovered or hidden, and fades follow the new length.
     */
    fun trimClipEndToFrame(
        trackIndex: Int,
        clipId: String,
        rawEndFrame: Long,
        snapToleranceFrames: Long = DEFAULT_SNAP_TOLERANCE_FRAMES
    ) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val snappedEnd = snapForEditMode(rawEndFrame, snapToleranceFrames)
        val newLength = (snappedEnd - clip.timelineStartFrame).coerceAtLeast(MIN_CLIP_FRAMES)
        if (newLength == clip.lengthFrames) return

        val updated = clip.copy(lengthFrames = newLength).clampedFades()
        val trimmed = track.clips.map { if (it.id == clipId) updated else it }
        val afterClips = if (settings.value.editMode == EditMode.SHUFFLE) {
            packShuffledFrom(trimmed, clipId)
        } else trimmed

        pushClipEdit(trackIndex, "Trim out point of ${clip.name}", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
    }

    /**
     * Pro Tools trim: drags the in point of a clip to [rawStartFrame]. The audio
     * underneath stays locked to the timeline, so the source offset moves with it.
     */
    fun trimClipStartToFrame(
        trackIndex: Int,
        clipId: String,
        rawStartFrame: Long,
        snapToleranceFrames: Long = DEFAULT_SNAP_TOLERANCE_FRAMES
    ) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val snappedStart = snapForEditMode(rawStartFrame, snapToleranceFrames).coerceAtLeast(0L)
        // Dragging the in point left uncovers audio that is already in the take,
        // so it can only travel as far as sourceStartFrame allows. Nothing is
        // ever invented past the head of the source.
        val delta = (snappedStart - clip.timelineStartFrame).coerceAtLeast(-clip.sourceStartFrame)
        if (delta == 0L) return

        val newLength = (clip.lengthFrames - delta).coerceAtLeast(MIN_CLIP_FRAMES)
        val sourceStart = (clip.sourceStartFrame + delta).coerceAtLeast(0L)
        val updated = clip.copy(
            timelineStartFrame = snappedStart,
            sourceStartFrame = sourceStart,
            lengthFrames = newLength
        ).clampedFades()

        val trimmed = track.clips.map { if (it.id == clipId) updated else it }
        val afterClips = if (settings.value.editMode == EditMode.SHUFFLE) {
            packShuffledFrom(trimmed, clipId)
        } else trimmed

        pushClipEdit(trackIndex, "Trim in point of ${clip.name}", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
    }

    private fun AudioClip.clampedFades(): AudioClip {
        val fadeIn = fadeInFrames.coerceIn(0L, lengthFrames)
        val fadeOut = fadeOutFrames.coerceIn(0L, lengthFrames)
        return if (fadeIn == fadeInFrames && fadeOut == fadeOutFrames) this
        else copy(fadeInFrames = fadeIn, fadeOutFrames = fadeOut)
    }

    fun duplicateClip(trackIndex: Int, clipId: String) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val duplicated = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            timelineStartFrame = clip.timelineStartFrame + clip.lengthFrames
        )

        val beforeClips = track.clips
        val afterClips = track.clips + duplicated

        undoManager.pushAction(SessionCommand.EditClips("Duplicate ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, totalLengthFrames = max(session.value.totalLengthFrames, duplicated.timelineStartFrame + duplicated.lengthFrames), revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
    }

    fun renameClip(trackIndex: Int, clipId: String, newName: String) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(name = newName)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Rename ${clip.name} to $newName", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        requestJournalWrite(session.value)
    }

    fun setClipGain(trackIndex: Int, clipId: String, gainDb: Float) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.withGainDb(gainDb)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips(String.format(java.util.Locale.US, "Gain ${clip.name} (%+.1f dB)", gainDb), trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
    }

    fun setClipFadeIn(trackIndex: Int, clipId: String, fadeInMs: Float, curve: com.example.audio.model.FadeCurve = com.example.audio.model.FadeCurve.EQUAL_POWER) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val fadeInFrames = (fadeInMs * 0.001f * sampleRate).toLong().coerceAtLeast(0L)
        val updated = clip.copy(fadeInFrames = fadeInFrames, fadeInCurve = curve)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Fade In ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
    }

    fun setClipFadeOut(trackIndex: Int, clipId: String, fadeOutMs: Float, curve: com.example.audio.model.FadeCurve = com.example.audio.model.FadeCurve.EQUAL_POWER) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val fadeOutFrames = (fadeOutMs * 0.001f * sampleRate).toLong().coerceAtLeast(0L)
        val updated = clip.copy(fadeOutFrames = fadeOutFrames, fadeOutCurve = curve)

        val beforeClips = track.clips
        val afterClips = track.clips.map { if (it.id == clipId) updated else it }

        undoManager.pushAction(SessionCommand.EditClips("Fade Out ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
    }

    /**
     * Pastes the selected clip at the next chorus section (e.g. 4 bars ahead).
     */
    fun pasteClipAsChorusSection(trackIndex: Int, clipId: String, barsAhead: Int = 4) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        // Real bar length from session tempo (120 BPM @ 48 kHz = 96 000 frames/bar)
        val beatFrames = (sampleRate * 60f / session.value.tempoBpm).toLong().coerceAtLeast(1L)
        val barFrames = beatFrames * 4
        val targetStart = clip.timelineStartFrame + (barsAhead * barFrames)

        val pasted = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Chorus +${barsAhead}B)",
            timelineStartFrame = targetStart
        )

        val beforeClips = track.clips
        val afterClips = track.clips + pasted

        undoManager.pushAction(SessionCommand.EditClips("Chorus Paste ${clip.name}", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        val maxFrames = max(session.value.totalLengthFrames, targetStart + clip.lengthFrames)
        session.update { it.copy(tracks = newTracks, totalLengthFrames = maxFrames, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
        uiState.update { it.copy(statusMessage = "Chorus Paste: \"${clip.name}\" placed at Bar +$barsAhead!") }
    }

    /**
     * Pastes a doubled chorus layer onto a target track with stereo chorus adaptation.
     */
    fun pasteClipAsDoubledChorus(sourceTrackIndex: Int, clipId: String, targetTrackIndex: Int) {
        val srcTrack = session.value.tracks.getOrNull(sourceTrackIndex) ?: return
        val targetTrack = session.value.tracks.getOrNull(targetTrackIndex) ?: srcTrack
        val clip = srcTrack.clips.find { it.id == clipId } ?: return

        // 12ms micro timing offset (576 frames @ 48kHz)
        val doubledStart = clip.timelineStartFrame + 576L
        val doubledClip = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Chorus Double)",
            timelineStartFrame = doubledStart,
            trackIndex = targetTrack.index,
            gainLinear = clip.gainLinear * 0.85f // subtle attenuation for double
        )

        val beforeClips = targetTrack.clips
        val afterClips = targetTrack.clips + doubledClip

        // Enable stereo chorus plugin on target track
        val newChorusConfig = targetTrack.chorusConfig.copy(
            enabled = true,
            rateHz = 1.4f,
            depthMs = 4.2f,
            stereoSpread = 0.90f,
            mix = 0.45f
        )
        updateTrackChorus(targetTrack.index, newChorusConfig)

        undoManager.pushAction(SessionCommand.EditClips("Chorus Doubler Layer", targetTrack.index, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == targetTrack.index) it.copy(clips = afterClips) else it }
        session.update { it.copy(tracks = newTracks, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(targetTrackIndex)
        uiState.update { it.copy(statusMessage = "Created Chorus Doubler Layer on ${targetTrack.name}!") }
    }

    /**
     * Fills 4-bar chorus section with crossfaded repeats of the clip.
     */
    fun adaptClipToChorusLoop(trackIndex: Int, clipId: String, repeatCount: Int = 3) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return

        val fadeFrames = 480L // 10ms crossfade
        val newClips = mutableListOf<AudioClip>()
        var currentStart = clip.timelineStartFrame

        for (r in 0..repeatCount) {
            val repClip = clip.copy(
                id = if (r == 0) clip.id else UUID.randomUUID().toString(),
                name = if (r == 0) clip.name else "${clip.name} (Rpt $r)",
                timelineStartFrame = currentStart,
                fadeInFrames = if (r > 0) fadeFrames else clip.fadeInFrames,
                fadeOutFrames = if (r < repeatCount) fadeFrames else clip.fadeOutFrames
            )
            newClips.add(repClip)
            currentStart += (clip.lengthFrames - fadeFrames)
        }

        val beforeClips = track.clips
        val afterClips = track.clips.filter { it.id != clipId } + newClips

        undoManager.pushAction(SessionCommand.EditClips("Adapt Chorus Loop", trackIndex, beforeClips, afterClips))
        val newTracks = session.value.tracks.map { if (it.index == trackIndex) it.copy(clips = afterClips) else it }
        val maxFrames = max(session.value.totalLengthFrames, currentStart + clip.lengthFrames)
        session.update { it.copy(tracks = newTracks, totalLengthFrames = maxFrames, revision = it.revision + 1) }
        requestJournalWrite(session.value)
        scheduleRebuildTrack(trackIndex)
        uiState.update { it.copy(statusMessage = "Adapted 4-bar chorus loop with equal-power crossfades!") }
    }

    fun showClipBin(show: Boolean) {
        uiState.update { it.copy(showClipBin = show) }
    }

    fun moveClipToFrame(
        trackIndex: Int,
        clipId: String,
        rawTargetFrame: Long,
        snapToleranceFrames: Long = DEFAULT_SNAP_TOLERANCE_FRAMES
    ) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val mode = settings.value.editMode

        val snappedFrame = snapForEditMode(rawTargetFrame, snapToleranceFrames).coerceAtLeast(0L)
        if (snappedFrame == clip.timelineStartFrame) return

        val moved = track.clips.map { if (it.id == clipId) it.copy(timelineStartFrame = snappedFrame) else it }
        val afterClips = if (mode == EditMode.SHUFFLE) packShuffledFrom(moved, clipId) else moved

        pushClipEdit(trackIndex, "Move ${clip.name} to frame $snappedFrame", track.clips, afterClips)
        applyClipsToTrack(trackIndex, afterClips)
    }

    /**
     * Snaps an edit for the active edit mode. Grid is a hard snap, so every drop
     * lands on a grid line; Slip follows the magnetic toggle; Shuffle snaps to
     * neighbouring clip edges; Spot never snaps, it places the drop exactly and
     * reports the landed time on [endClipGesture].
     */
    private fun snapForEditMode(rawFrame: Long, toleranceFrames: Long): Long {
        val edit = settings.value
        val frame = rawFrame.coerceAtLeast(0L)
        val current = session.value
        return when (edit.editMode) {
            EditMode.SLIP -> if (edit.isMagneticSnapEnabled) {
                SnapEngine.snapFrame(frame, current, true, edit.gridResolution, toleranceFrames)
            } else frame
            EditMode.GRID -> {
                val resolution = if (edit.gridResolution == GridResolution.CLIP_BOUNDARIES) {
                    GridResolution.BEAT
                } else edit.gridResolution
                SnapEngine.snapFrame(frame, current, true, resolution, HARD_SNAP_TOLERANCE_FRAMES)
            }
            EditMode.SHUFFLE -> SnapEngine.snapFrame(
                frame,
                current,
                true,
                GridResolution.CLIP_BOUNDARIES,
                toleranceFrames
            )
            EditMode.SPOT -> frame
        }
    }

    /**
     * Shuffle packing: everything after [fromClipId] sits flush against its
     * neighbour, so a shuffle drag or trim never leaves a gap or an overlap.
     */
    private fun packShuffledFrom(clips: List<AudioClip>, fromClipId: String): List<AudioClip> {
        val sorted = clips.sortedBy { it.timelineStartFrame }.toMutableList()
        val startIndex = sorted.indexOfFirst { it.id == fromClipId }
        if (startIndex < 0) return clips
        for (i in (startIndex + 1) until sorted.size) {
            val previous = sorted[i - 1]
            val flushStart = previous.timelineStartFrame + previous.lengthFrames
            if (sorted[i].timelineStartFrame != flushStart) {
                sorted[i] = sorted[i].copy(timelineStartFrame = flushStart)
            }
        }
        return sorted
    }

    /** Frames per grid step at the session tempo. */
    private fun gridStepFrames(resolution: GridResolution): Long {
        val step = SnapEngine.framesPerStep(resolution, sampleRate, session.value.tempoBpm)
        val beat = SnapEngine.framesPerStep(GridResolution.BEAT, sampleRate, session.value.tempoBpm)
        return if (step > 0L) step else beat
    }

    fun toggleMagneticSnap() {
        settings.update { it.copy(isMagneticSnapEnabled = !it.isMagneticSnapEnabled) }
    }

    fun cycleGridResolution() {
        val current = settings.value.gridResolution
        val all = com.example.audio.model.GridResolution.values()
        val nextIdx = (current.ordinal + 1) % all.size
        settings.update { it.copy(gridResolution = all[nextIdx]) }
    }

    fun nudgeSelectedClip(trackIndex: Int, clipId: String, forward: Boolean) {
        val track = session.value.tracks.getOrNull(trackIndex) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val step = gridStepFrames(settings.value.gridResolution)
        val target = if (forward) {
            clip.timelineStartFrame + step
        } else {
            max(0L, clip.timelineStartFrame - step)
        }
        moveClipToFrame(trackIndex, clipId, target)
    }


    companion object {
        /** Shortest clip an edit may leave behind: 100 ms at 48 kHz. */
        const val MIN_CLIP_FRAMES = 4800L

        /** ~20 ms of drag has to reach a snap target before the edit sticks to it. */
        const val DEFAULT_SNAP_TOLERANCE_FRAMES = 960L

        /** Grid mode is a hard snap: any drag lands on the nearest grid line. */
        const val HARD_SNAP_TOLERANCE_FRAMES = 12_000_000L
    }
}
