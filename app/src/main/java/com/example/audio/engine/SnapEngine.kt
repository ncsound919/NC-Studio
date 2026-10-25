package com.example.audio.engine

import com.example.audio.model.GridResolution
import com.example.audio.model.ProjectSession
import kotlin.math.abs

object SnapEngine {

    /**
     * Calculates the magnetically snapped target frame position.
     * Snaps to grid intervals (Bar, Beat, 16th) or existing clip boundaries if enabled.
     */
    fun snapFrame(
        targetFrame: Long,
        session: ProjectSession,
        isSnapEnabled: Boolean,
        resolution: GridResolution,
        toleranceFrames: Long = 960L // ~20ms tolerance window at 48kHz
    ): Long {
        if (!isSnapEnabled) return targetFrame

        var bestSnapCandidate = targetFrame
        var minDistance = toleranceFrames + 1L

        // 1. Check Grid Interval Snap
        if (resolution != GridResolution.CLIP_BOUNDARIES) {
            val interval = resolution.frames
            if (interval > 0L) {
                val nearestGrid = ((targetFrame + (interval / 2)) / interval) * interval
                val dist = abs(nearestGrid - targetFrame)
                if (dist < minDistance) {
                    minDistance = dist
                    bestSnapCandidate = nearestGrid
                }
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
