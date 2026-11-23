package com.ncstudio.daw.audio.model

/**
 * A/B mixer capture-recall snapshots (the desktop MixRack behaviour).
 *
 * A snapshot holds only mixer-level state — gain, pan, mute, solo and the
 * reverb send per track plus the master fader. It is deliberately narrower
 * than the whole session: recalling must never touch clips, plugins or
 * routing, so an A/B compare is always safe while audio runs.
 */
data class TrackMixerState(
    val index: Int,
    val gainDb: Float,
    val pan: Float,
    val isMuted: Boolean,
    val isSoloed: Boolean,
    val reverbSendLevel: Float,
)

data class MixerSnapshot(
    val name: String,
    val capturedAtMs: Long,
    val masterGainDb: Float,
    val tracks: List<TrackMixerState>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()
}
