package com.example.audio.engine

import com.example.audio.model.AudioClip
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached per-clip waveform peak envelopes (min/max) rendered from the actual
 * audio data, replacing the previous placeholder sine rendering.
 *
 * Peaks are stored interleaved [min, max] per bin so the timeline can draw
 * true sample-derived waveforms without re-scanning buffers on every frame.
 */
object WaveformCache {

    const val MAX_BINS = 512

    private val cache = ConcurrentHashMap<String, Array<FloatArray>>()
    private val accessOrder = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Computes and caches the min/max envelope for every clip in [clips],
     * reading from the per-path [sources] pool.
     */
    fun refreshClips(clips: List<AudioClip>, sources: Map<String, FloatArray>) {
        for (clip in clips) {
            val src = sources[clip.sourcePath] ?: continue
            cache[clip.id] = computePeaks(src, clip.sourceStartFrame, clip.lengthFrames, size = src.size)
        }
        accessOrder.addAll(clips.map { it.id })
        trimIfNeeded()
    }

    fun remove(clipId: String) {
        cache.remove(clipId)
        accessOrder.remove(clipId)
    }

    /** Interleaved [min, max] bins for a clip, or null when not computed yet. */
    fun get(clipId: String): Array<FloatArray>? = cache[clipId]

    /**
     * Renders min/max peaks over [lengthFrames] frames of interleaved stereo
     * starting at [sourceStartFrame].
     */
    fun computePeaks(
        source: FloatArray,
        sourceStartFrame: Long,
        lengthFrames: Long,
        bins: Int = MAX_BINS,
        size: Int = source.size
    ): Array<FloatArray> {
        val usableBins = bins.coerceIn(16, MAX_BINS)
        val envelopes = Array(usableBins) { FloatArray(2) { 0f } }
        if (lengthFrames <= 0 || source.isEmpty()) return envelopes

        val framesPerBin = (lengthFrames / usableBins).coerceAtLeast(1)

        for (b in 0 until usableBins) {
            val binStart = sourceStartFrame + b * framesPerBin
            val binEnd = minOf(binStart + framesPerBin, sourceStartFrame + lengthFrames)
            var minL = 1f
            var maxL = -1f
            for (f in binStart until binEnd) {
                val idx = (f * 2).toInt()
                if (idx + 1 >= size) break
                val l = source[idx]
                val r = source[idx + 1]
                val sample = (kotlin.math.abs(l) + kotlin.math.abs(r)) * 0.5f
                // Keep signed envelope for visual waveform shape
                val signed = (l + r) * 0.5f
                if (signed < minL) minL = signed
                if (signed > maxL) maxL = signed
                if (sample < 1e-8f) {
                    minL = if (minL > 0f) 0f else minL
                    maxL = if (maxL < 0f) 0f else maxL
                }
            }
            envelopes[b] = floatArrayOf(minL.coerceIn(-1f, 1f), maxL.coerceIn(-1f, 1f))
        }
        return envelopes
    }

    private fun trimIfNeeded() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val victim = accessOrder.firstOrNull() ?: return
        remove(victim)
    }

    private const val MAX_CACHE_ENTRIES = 96
}
