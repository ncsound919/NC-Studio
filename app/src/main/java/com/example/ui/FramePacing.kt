package com.example.ui

import kotlin.math.roundToInt

data class FramePacingSnapshot(
    val meanMs: Float,
    val p95Ms: Float,
    val slowFramePercent: Float
)

/**
 * Rolling window of frame intervals. Keep the allocation-free recording path
 * cheap enough to call from a Choreographer frame callback.
 */
class FramePacingTracker(
    private val windowSize: Int = 120,
    private val slowFrameMs: Float = 16.7f
) {
    private val intervals = FloatArray(windowSize.coerceAtLeast(1))
    private var cursor = 0
    private var size = 0

    fun record(frameNanos: Long) {
        if (frameNanos <= 0L) return
        intervals[cursor] = frameNanos / 1_000_000f
        cursor = (cursor + 1) % intervals.size
        if (size < intervals.size) size++
    }

    fun snapshot(): FramePacingSnapshot? {
        if (size == 0) return null
        val sorted = intervals.copyOf(size).apply { sort() }
        val mean = sorted.average().toFloat()
        val p95 = sorted[((size - 1) * 0.95f).roundToInt().coerceIn(0, size - 1)]
        val slow = sorted.count { it > slowFrameMs }
        return FramePacingSnapshot(
            meanMs = mean,
            p95Ms = p95,
            slowFramePercent = slow * 100f / size
        )
    }

    fun reset() {
        cursor = 0
        size = 0
    }
}
