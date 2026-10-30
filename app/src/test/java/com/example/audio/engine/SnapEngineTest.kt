package com.example.audio.engine

import com.example.audio.model.AudioClip
import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapEngineTest {

    @Test
    fun framesPerStep_followsSessionTempo() {
        // 120 BPM at 48 kHz: one beat is 24 000 frames, one bar 96 000 frames.
        assertEquals(24000L, SnapEngine.framesPerStep(GridResolution.BEAT, 48000, 120f))
        assertEquals(96000L, SnapEngine.framesPerStep(GridResolution.BAR, 48000, 120f))
        assertEquals(6000L, SnapEngine.framesPerStep(GridResolution.SIXTEENTH, 48000, 120f))
        // 60 BPM halves the bar count per minute, so steps get twice as long.
        assertEquals(48000L, SnapEngine.framesPerStep(GridResolution.BEAT, 48000, 60f))
    }

    @Test
    fun snapFrame_snapsToNearestBeatWhenEnabled() {
        val session = ProjectSession(tracks = emptyList())
        // One beat at 120 BPM = 24000 frames; 24500 is inside the tolerance window.
        val snapped = SnapEngine.snapFrame(
            targetFrame = 24500L,
            session = session,
            isSnapEnabled = true,
            resolution = GridResolution.BEAT,
            toleranceFrames = 500L
        )

        assertEquals(24000L, snapped)
    }

    @Test
    fun snapFrame_snapsToClipBoundaryWhenNearby() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(
                    index = 0,
                    name = "Track 0",
                    colorHex = 0xFFFF5722,
                    clips = listOf(
                        AudioClip(
                            name = "Clip A",
                            sourcePath = "test",
                            lengthFrames = 9600L,
                            timelineStartFrame = 10000L,
                            trackIndex = 0
                        )
                    )
                )
            )
        )

        // Clip ends at 19600L. Target at 19550L should snap to 19600L.
        val snapped = SnapEngine.snapFrame(
            targetFrame = 19550L,
            session = session,
            isSnapEnabled = true,
            resolution = GridResolution.CLIP_BOUNDARIES,
            toleranceFrames = 200L
        )

        assertEquals(19600L, snapped)
    }

    @Test
    fun snapFrame_respectsSnapDisabled() {
        val session = ProjectSession(tracks = emptyList())
        val target = 4900L
        val snapped = SnapEngine.snapFrame(
            targetFrame = target,
            session = session,
            isSnapEnabled = false,
            resolution = GridResolution.BEAT
        )

        assertEquals(target, snapped)
    }
}
