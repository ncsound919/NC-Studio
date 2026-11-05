package com.example.audio.model

import java.util.UUID

/**
 * Comping: borrow a time range from another take into the take being heard.
 *
 * The comp is deliberately destructive. The borrowed audio becomes ordinary
 * clips of the active take, so playback, export, fades and every existing clip
 * edit keep working without a second audio path through the engine. Undo
 * restores the previous clip list, which is why nothing is written to disk here.
 */
fun List<AudioClip>.withCompedRange(
    source: List<AudioClip>,
    startFrame: Long,
    endFrame: Long
): List<AudioClip> {
    if (endFrame <= startFrame) return this
    val borrowed = source.mapNotNull {
        it.slice(maxOf(it.timelineStartFrame, startFrame), minOf(it.timelineEndFrame, endFrame))
    }
    // A comp from a take that has no audio in the range is a no-op rather than
    // a silence: the user asked for that performance, and silently erasing the
    // audible take instead would be destructive and surprising.
    if (borrowed.isEmpty()) return this
    // Only the spans the other take actually covers are replaced, so a partial
    // overlap keeps everything else exactly as it was.
    val kept = borrowed.fold(this) { clips, piece ->
        clips.flatMap { it.withoutRange(piece.timelineStartFrame, piece.timelineEndFrame) }
    }
    return (kept + borrowed).sortedBy { it.timelineStartFrame }
}

/** The frame just past the clip's last sample. */
val AudioClip.timelineEndFrame: Long
    get() = timelineStartFrame + lengthFrames

/**
 * Returns this clip minus [startFrame]..[endFrame]. A clip the range misses is
 * returned untouched (same instance) so callers can tell "nothing changed" by
 * identity; a clip the range cuts in half becomes two clips that keep their
 * audio alignment through [AudioClip.sourceStartFrame].
 */
private fun AudioClip.withoutRange(startFrame: Long, endFrame: Long): List<AudioClip> {
    if (timelineEndFrame <= startFrame || timelineStartFrame >= endFrame) return listOf(this)
    return buildList {
        if (timelineStartFrame < startFrame) slice(timelineStartFrame, startFrame)?.let { add(it) }
        if (timelineEndFrame > endFrame) slice(endFrame, timelineEndFrame)?.let { add(it) }
    }
}

/**
 * Copies the [from]..[to] window of this clip into a new clip. Fades are reset
 * on any edge the original did not have: an edit boundary should be sample
 * accurate, and the user adds a crossfade where they want one.
 */
private fun AudioClip.slice(from: Long, to: Long): AudioClip? {
    val length = to - from
    if (length <= 0L) return null
    return copy(
        id = UUID.randomUUID().toString(),
        sourceStartFrame = sourceStartFrame + (from - timelineStartFrame),
        timelineStartFrame = from,
        lengthFrames = length,
        fadeInFrames = if (from == timelineStartFrame) fadeInFrames.coerceAtMost(length) else 0L,
        fadeOutFrames = if (to == timelineEndFrame) fadeOutFrames.coerceAtMost(length) else 0L
    )
}
