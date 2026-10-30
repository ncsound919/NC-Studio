package com.example.audio.engine

import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import kotlin.math.abs

object SnapEngine {

    /**
     * Frames per grid step for [resolution] at the session tempo. The frame values
     * baked into [GridResolution] are only 120 BPM reference labels: snapping has
     * to follow the tempo the ruler draws, or the grid drifts away from the bars.
     */
    fun framesPerStep(
        resolution: GridResolution,
        sampleRate: Int = 48000,
        tempoBpm: Float = 120f,
        beatsPerBar: Int = 4
    ): Long {
        val beatFrames = (sampleRate * 60.0 / tempoBpm.coerceAtLeast(20f)).toLong().coerceAtLeast(1L)
        return when (resolution) {
            GridResolution.BAR -> beatFrames * beatsPerBar.coerceAtLeast(1)
            GridResolution.BEAT -> beatFrames
            GridResolution.SIXTEENTH -> (beatFrames / 4).coerceAtLeast(1L)
            GridResolution.CLIP_BOUNDARIES -> 0L
        }
    }

    /**
     * Calculates the magnetically snapped target frame position.
     * Snaps to grid intervals (bar, beat, 1/16 at session tempo) or existing clip
     * boundaries if enabled.
     */
    fun snapFrame(
        targetFrame: Long,
        session: ProjectSession,
        isSnapEnabled: Boolean,
        resolution: GridResolution,
        toleranceFrames: Long = 960L, // ~20ms tolerance window at 48kHz
        sampleRate: Int = 48000,
        beatsPerBar: Int = 4
    ): Long {
        if (!isSnapEnabled) return targetFrame

        var bestSnapCandidate = targetFrame
        var minDistance = toleranceFrames + 1L

        // 1. Check Grid Interval Snap (tempo aware; 0 frames for CLIP_BOUNDARIES)
        val interval = framesPerStep(resolution, sampleRate, session.tempoBpm, beatsPerBar)
        if (interval > 0L) {
            val nearestGrid = ((targetFrame + (interval / 2)) / interval) * interval
            val dist = abs(nearestGrid - targetFrame)
            if (dist < minDistance) {
                minDistance = dist
                bestSnapCandidate = nearestGrid
            }
        }

        // 2. Check Clip Boundary Snap (Start & End of all clips in session)
        for (trk in session.tracks) {
            for (clip in trk.clips) {
                val startFrame = clip.timelineStartFrame
                val endFrame = clip.timelineStartFrame + clip.lengthFrames

                val distStart = abs(startFrame - targetFrame)
                if (distStart < minDistance) {
                    minDistance = distStart
                    bestSnapCandidate = startFrame
                }

                val distEnd = abs(endFrame - targetFrame)
                if (distEnd < minDistance) {
                    minDistance = distEnd
                    bestSnapCandidate = endFrame
                }
            }
        }

        return bestSnapCandidate
    }
}
