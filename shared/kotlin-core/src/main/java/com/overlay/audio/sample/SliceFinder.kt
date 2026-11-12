package com.overlay.audio.sample

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Onset slicing for samplers, shared by every app that consumes `kotlin-core`.
 *
 * This is the native, model-free half of what the open source `earshot` CLI does
 * when it "finds the moments" in a recording: look at the waveform envelope,
 * decide where a sound *starts*, and cut there. earshot itself is a Python CLI
 * that loads models for 20-40 s per call, so it cannot ship inside an Android
 * app; the detectors it demonstrates can, which is what this file is.
 *
 * Deliberately dependency-free and O(n): a 10 s mono sample is analysed in a few
 * milliseconds on the frame that calls it. No FFT, no I/O, no allocation per
 * sample, and the same input always gives the same cuts.
 *
 * Two shapes count as an onset:
 *  - the envelope crossing [Settings.gateFraction] after being below it,
 *    which is where a new sound begins in a recording with gaps;
 *  - a rise of [Settings.onsetRiseFraction] inside one hop, which catches a
 *    hit in continuous material (a drum loop whose hats never go quiet) where the
 *    gate alone never reopens.
 */
object SliceFinder {

    const val DEFAULT_SAMPLE_RATE = 48_000

    /**
     * @param gateFraction level that counts as "sound is present", against the
     *   loudest hop in the file.
     * @param onsetRiseFraction single-hop jump that counts as an onset even
     *   without a dip below the gate.
     * @param hopMs envelope resolution.
     * @param minSliceMs shortest slice a cut may produce; also the refractory
     *   period between cuts.
     * @param maxSlices cap on cuts (not slices).
     * @param snapToZeroCrossing move each cut to the nearest sign change so a
     *   slice cannot begin on a sample step and click.
     */
    data class Settings(
        val gateFraction: Float = 0.12f,
        val onsetRiseFraction: Float = 0.35f,
        val hopMs: Int = 5,
        val minSliceMs: Int = 60,
        val maxSlices: Int = 32,
        val snapToZeroCrossing: Boolean = true,
    )

    /**
     * Cut frames, ascending, strictly inside the sample: position 0 and the end
     * are implied by the first and last slice and are never returned.
     */
    fun findCuts(
        samples: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        settings: Settings = Settings(),
    ): List<Int> {
        if (samples.size < 8) return emptyList()
        val rate = sampleRate.coerceAtLeast(1_000)
        val hop = max(1, rate * settings.hopMs.coerceIn(1, 50) / 1000)
        val hops = samples.size / hop
        if (hops < 4) return emptyList()

        // Envelope: peak absolute value per hop. Peak (not RMS) because a slice
        // boundary is about where a transient lands, and RMS lags the attack.
        val env = FloatArray(hops) { h ->
            var peak = 0f
            val from = h * hop
            val to = min(samples.size, from + hop)
            for (i in from until to) {
                val v = abs(samples[i])
                if (v > peak) peak = v
            }
            peak
        }
        val loudest = env.max()
        if (loudest <= 1e-6f) return emptyList()

        val gate = loudest * settings.gateFraction.coerceIn(0.01f, 0.95f)
        val rise = loudest * settings.onsetRiseFraction.coerceIn(0.05f, 1f)
        val minGap = max(hop, rate * settings.minSliceMs.coerceIn(10, 5_000) / 1000)
        val limit = settings.maxSlices.coerceIn(1, 128)

        val cuts = ArrayList<Int>(limit)
        var below = env[0] < gate
        for (h in 1 until hops) {
            val level = env[h]
            val loud = level >= gate
            val crossedGate = below && loud
            val sharpRise = level - env[h - 1] >= rise
            if ((crossedGate || sharpRise) && (cuts.isNotEmpty() || soundBefore(env, h, gate))) {
                val candidate = h * hop
                // A cut has to leave a real slice behind it: a full minimum slice,
                // not the half-slice this allowed at first, which produced a stub
                // pad of a few milliseconds at the end of a file.
                val hasRoomAfter = samples.size - candidate >= minGap
                if (hasRoomAfter && candidate - (cuts.lastOrNull() ?: 0) >= minGap) {
                    val cut = if (settings.snapToZeroCrossing) {
                        zeroCrossing(samples, candidate, hop)
                    } else {
                        candidate
                    }
                    if (cut > 0 && cut < samples.size && cut - (cuts.lastOrNull() ?: 0) >= minGap) {
                        cuts.add(cut)
                        if (cuts.size >= limit) break
                    }
                }
            }
            below = !loud
        }
        return cuts
    }

    /** True when at least one hop before [untilHop] reached the gate. */
    private fun soundBefore(env: FloatArray, untilHop: Int, gate: Float): Boolean {
        for (h in 0 until untilHop) if (env[h] >= gate) return true
        return false
    }

    /**
     * Nearest sign change to [frame] within ±[window] frames, else [frame] itself.
     * A cut on a sign change starts the slice at (or across) silence, which is
     * what keeps a chopped hit free of the click a mid-step cut adds.
     */
    fun zeroCrossing(samples: FloatArray, frame: Int, window: Int): Int {
        if (samples.size < 2) return 0
        val first = (frame - window).coerceIn(1, samples.size - 1)
        val last = (frame + window).coerceIn(first, samples.size - 1)
        var best = frame.coerceIn(1, samples.size - 1)
        var bestDistance = Int.MAX_VALUE
        for (i in first..last) {
            val a = samples[i - 1]
            val b = samples[i]
            if ((a < 0f && b >= 0f) || (a >= 0f && b < 0f)) {
                val distance = abs(i - frame)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
        }
        return best
    }

    /** What the file sounds like in measurements: the readout half of "describe". */
    data class Profile(
        val durationMs: Float,
        val peak: Float,
        val rms: Float,
        val crest: Float,
        val attackMs: Float,
        val decayMs: Float,
        /** Zero-crossing rate against Nyquist: 0 dark, 1 bright/noisy. */
        val brilliance: Float,
    )

    fun profile(samples: FloatArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): Profile {
        if (samples.isEmpty()) return Profile(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val rate = sampleRate.coerceAtLeast(1_000)
        val frameMs = 1_000f / rate

        var peak = 0f
        var peakAt = 0
        var energy = 0.0
        var crossings = 0
        for (i in samples.indices) {
            val v = samples[i]
            val a = abs(v)
            if (a > peak) {
                peak = a
                peakAt = i
            }
            energy += a.toDouble() * a
            if (i > 0 && ((samples[i - 1] < 0f) != (v < 0f))) crossings++
        }
        val rms = sqrt(energy / samples.size).toFloat()

        // -20 dB below the peak is the "sound has effectively stopped" line.
        val floor = max(0.1f * peak, 1e-6f)
        var firstSound = 0
        while (firstSound < samples.size && abs(samples[firstSound]) < floor) firstSound++
        var silentAt = peakAt
        while (silentAt < samples.size && abs(samples[silentAt]) > floor) silentAt++

        val attackMs = if (peakAt > firstSound) (peakAt - firstSound) * frameMs else 0f
        val decayMs = (silentAt - peakAt).coerceAtLeast(0) * frameMs
        val brilliance = if (samples.size > 1) {
            (2f * crossings / (samples.size - 1)).coerceIn(0f, 1f)
        } else {
            0f
        }
        return Profile(
            durationMs = samples.size * frameMs,
            peak = peak,
            rms = rms,
            crest = if (rms > 1e-6f) peak / rms else 0f,
            attackMs = attackMs,
            decayMs = decayMs,
            brilliance = brilliance,
        )
    }
}
