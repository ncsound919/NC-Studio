package com.ncstudio.daw.ui

import com.ncstudio.daw.audio.model.ProjectSession
import com.ncstudio.daw.audio.model.TrackData

/**
 * Tracks a brand-new project opens with.
 *
 * A blank session used to allocate all 16 engine channels with genre names
 * ("Kick Drum", "Lead Vocal"...), which made an empty project look like
 * somebody else's template and buried the arrangement under empty lanes. Four
 * empty tracks is an empty desk you can actually see; more can be added.
 */
internal const val DEFAULT_BLANK_TRACKS = 4

/** Hard ceiling of the DSP engine: 16 fixed channel strips. */
internal const val MAX_SESSION_TRACKS = 16

/** Track colours handed out in order, shared by blank sessions and new tracks. */
private val BLANK_TRACK_COLORS = longArrayOf(
    0xFF00BCD4, // cyan
    0xFFFF9800, // amber
    0xFF8BC34A, // green
    0xFFE91E63, // pink
    0xFF9C27B0, // purple
    0xFF2196F3, // blue
    0xFFFF5722, // deep orange
    0xFF009688, // teal
    0xFFFFEB3B, // yellow
    0xFF3F51B5, // indigo
    0xFF4CAF50, // green (dark)
    0xFFCDDC39, // lime
    0xFF673AB7, // deep purple
    0xFFF44336, // red
    0xFFFF4081, // magenta
    0xFF03A9F4  // light blue
)

/**
 * A neutral, empty track. No stock clips are baked in: audio arrives from
 * import or recording only.
 */
internal fun blankTrack(index: Int, name: String = "Audio ${index + 1}"): TrackData =
    TrackData(
        index = index,
        name = name,
        colorHex = BLANK_TRACK_COLORS[index % BLANK_TRACK_COLORS.size],
        gainDb = 0f,
        pan = 0f,
        reverbSendLevel = 0f,
        clips = emptyList()
    )

internal fun createInitialSession(
    trackCount: Int = DEFAULT_BLANK_TRACKS
): ProjectSession {
    val count = trackCount.coerceIn(1, MAX_SESSION_TRACKS)
    return ProjectSession(tracks = (0 until count).map { blankTrack(it) })
}
