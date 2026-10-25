package com.example.audio.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * High-quality sample-rate conversion & channel normalization.
 *
 * Uses a Hann-windowed sinc (Whittaker-Shannon ideal interpolation kernel, 32 taps)
 * which is transparent for music content and dramatically better than linear
 * interpolation when converting between 44.1/48/96 kHz session rates.
 *
 * Also normalizes any input layout (mono / stereo / >2ch interleaved) to
 * session-standard interleaved stereo at the project sample rate, so the
 * playback engine only ever deals with one canonical buffer format.
 */
object AudioResampler {

    private const val TAPS = 32
    private val sincWindow = FloatArray(TAPS) { i ->
        val m = (TAPS - 1) / 2.0
        val x = (i - m) / m
        // Hann window over the kernel width
        val w = 0.5 + 0.5 * cos(2.0 * PI * x)
        val s = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        (w * s).toFloat()
    }

    const val SESSION_SAMPLE_RATE = 48000

    /**
     * Normalizes arbitrary interleaved audio into session-standard stereo
     * at the project sample rate (default 48 kHz).
     *
     * @param samples interleaved frames at [srcChannels] channels / [srcRate]
     * @param srcChannels channel count of the source buffer (1 = mono, 2 = stereo, >2 downmixed)
     * @return interleaved stereo FloatArray at [dstRate]
     */
    fun normalizeToSessionStereo(
        samples: FloatArray,
        srcChannels: Int,
        srcRate: Int = SESSION_SAMPLE_RATE,
        dstRate: Int = SESSION_SAMPLE_RATE
    ): FloatArray {
        val stereoInterleaved = when (srcChannels) {
            0 -> FloatArray(0)
            1 -> upmixMono(samples)
            else -> foldToStereo(samples, srcChannels) // 2ch passes through unchanged
        }
        return if (srcRate == dstRate) stereoInterleaved
        else resampleStereo(stereoInterleaved, true, srcRate, dstRate)
    }

    /**
     * Folds any N-channel interleaved buffer into averaged L+R pairs
     * (returns 2-channel interleaved).
     */
    private fun foldToStereo(samples: FloatArray, channels: Int): FloatArray {
        if (channels <= 2) return samples
        val frames = samples.size / channels
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            var lCount = 0
            var rCount = 0
            for (c in 0 until channels) {
                val s = samples[f * channels + c]
                if (c % 2 == 0) { l += s; lCount++ } else { r += s; rCount++ }
            }
            out[f * 2] = if (lCount > 0) l / lCount else 0f
            out[f * 2 + 1] = if (rCount > 0) r / rCount else 0f
        }
        return out
    }

    private fun upmixMono(samples: FloatArray): FloatArray {
        val frames = samples.size
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = samples[f]
            out[f * 2 + 1] = samples[f]
        }
        return out
    }

    /**
     * Windowed-sinc resampling for interleaved stereo (or mono treated as stereo=false).
     */
    fun resampleStereo(
        samples: FloatArray,
        isStereoSrc: Boolean,
        srcRate: Int,
        dstRate: Int
    ): FloatArray {
        if (srcRate == dstRate) return samples
        if (srcRate <= 0 || dstRate <= 0) return samples

        val channelsIn = if (isStereoSrc) 2 else 1
        val srcFrames = samples.size / channelsIn
        if (srcFrames == 0) return FloatArray(0)

        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val dstFrames = (srcFrames * ratio).toInt().coerceAtLeast(1)
        val channelsOut = if (isStereoSrc) 2 else 1
        val out = FloatArray(dstFrames * channelsOut)

        val step = 1.0 / ratio // source frames per destination frame
        val tapsHalf = TAPS / 2

        for (dst in 0 until dstFrames) {
            val srcPos = dst * step
            val center = srcPos.toInt()
            val frac = srcPos - center

            for (ch in 0 until channelsOut) {
                var acc = 0.0
                for (t in 0 until TAPS) {
                    val tapPos = center - tapsHalf + t + (if (frac > 0.0) 1 else 0)
                    if (tapPos < 0 || tapPos >= srcFrames) continue
                    val tNorm = t - tapsHalf - frac + (if (frac > 0.0) 1 else 0)
                    val kernel = sincKernel(tNorm)
                    acc += samples[tapPos * channelsIn + ch] * kernel
                }
                out[dst * channelsOut + ch] = acc.toFloat().coerceIn(-1f, 1f)
                if (!isStereoSrc) break // mono source: single channel
            }
            // mono source written once per channel below handled by channelsOut
        }
        return out
    }

    /**
     * Continuous (non-integer-tap) windowed sinc kernel value.
     * Uses the discrete Hann-windowed kernel scaled by distance from center.
     */
    private fun sincKernel(t: Double): Float {
        val x = abs(t)
        if (x >= TAPS / 2.0) return 0f
        // Linear blend of the two nearest discrete kernel taps (windowed sinc derivative)
        val idxFloor = kotlin.math.floor(t).toInt()
        val frac = (t - idxFloor).toFloat()
        val m = (TAPS - 1) / 2.0

        fun kernelAt(i: Int): Float {
            val xi = i - m
            val xn = (xi / m)
            val w = 0.5 + 0.5 * cos(2.0 * PI * xn)
            val s = if (abs(xi) < 1e-9) 1.0 else sin(PI * xi) / (PI * xi)
            return (w * s).toFloat()
        }

        val k0 = kernelAt(idxFloor)
        val k1 = kernelAt(idxFloor + 1)
        val s = if (x < 1e-9) 1f else (sin(PI * x) / (PI * x)).toFloat()
        return (k0 * (1f - frac) + k1 * frac) * s
    }

    /**
     * Convenience: resample a single-channel buffer (e.g. latency chirps).
     */
    fun resampleMono(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray =
        if (srcRate == dstRate) samples else resampleStereo(samples, false, srcRate, dstRate)
}
