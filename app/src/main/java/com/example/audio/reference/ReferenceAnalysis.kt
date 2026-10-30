package com.example.audio.reference

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Reference-track analysis.
 *
 * A commercial mix is only useful as a mixing reference when you can measure it
 * the way the mastering world measures a master: integrated loudness by
 * ITU-R BS.1770-4 (K-weighting, 400 ms blocks at 75% overlap, absolute and
 * relative gating), true peak by 4x oversampling, and a tonal-balance picture
 * that is independent of loudness so the comparison cannot be won by simply
 * turning the reference up.
 *
 * Design notes borrowed from Lemon Studio (Rust, GPL-3.0 - designs read as a
 * reference, no code copied):
 *  - [crates/os-dsp/src/level.rs] treats every gain as an explicit, documented
 *    curve with a stated reference point instead of an implicit multiplier. The
 *    same discipline lives here: every number this file produces has a unit, a
 *    standard and a stated tolerance.
 *  - [crates/os-dsp/src/mixer.rs] keeps the measurement path separate from the
 *    playback path, so nothing in the audio callback has to compute what the
 *    analyser already knows. [ReferenceProfile] follows that split: analysis
 *    happens once, on load, off the audio thread.
 *
 * Everything is offline: interleaved stereo float frames in, an immutable
 * profile out. No allocation happens per block and nothing here is touched by
 * the render thread.
 */

// ---------------------------------------------------------------------------
// Biquad primitives (RBJ / BS.1770 coefficient forms)
// ---------------------------------------------------------------------------

/** Direct-form-1 biquad with one channel of state. Coefficients are normalised by a0. */
internal class Biquad(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

/**
 * The two-stage K-weighting filter of ITU-R BS.1770-4: a high shelf (the head
 * model, +4 dB above ~2 kHz) into the "RLB" high-pass (the torso model).
 *
 * Coefficients follow the standard's analogue prototype evaluated with a
 * bilinear transform, the same construction used by libebur128 and pyloudnorm.
 * The analyser is validated against pyloudnorm on real masters in
 * `ReferenceAnalysisTest`, which is what pins these numbers down.
 */
internal object KWeighting {
    private const val SHELF_F0 = 1681.974450955533
    private const val SHELF_GAIN_DB = 3.999843853973347
    private const val SHELF_Q = 0.7071752369554196
    private const val SHELF_VB_EXP = 0.4996667741545416

    private const val HP_F0 = 38.13547087602444
    private const val HP_Q = 0.5003270373238773

    fun highShelf(sampleRate: Int): Biquad {
        val k = tan(PI * SHELF_F0 / sampleRate)
        val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
        val vb = vh.pow(SHELF_VB_EXP)
        val a0 = 1.0 + k / SHELF_Q + k * k
        val b0 = (vh + vb * k / SHELF_Q + k * k) / a0
        val b1 = 2.0 * (k * k - vh) / a0
        val b2 = (vh - vb * k / SHELF_Q + k * k) / a0
        val a1 = 2.0 * (k * k - 1.0) / a0
        val a2 = (1.0 - k / SHELF_Q + k * k) / a0
        return Biquad(b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat())
    }

    fun highPass(sampleRate: Int): Biquad {
        val k = tan(PI * HP_F0 / sampleRate)
        val a0 = 1.0 + k / HP_Q + k * k
        val b0 = 1.0 / a0
        val b1 = -2.0 / a0
        val b2 = 1.0 / a0
        val a1 = 2.0 * (k * k - 1.0) / a0
        val a2 = (1.0 - k / HP_Q + k * k) / a0
        return Biquad(b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat())
    }
}

// ---------------------------------------------------------------------------
// Loudness (ITU-R BS.1770-4) and true peak (BS.1770-4 / EBU R128)
// ---------------------------------------------------------------------------

/** Everything the loudness meter reports, in LUFS / dBTP. */
data class LoudnessStats(
    /** Gated integrated loudness, LUFS. -60 when the file is silent. */
    val integratedLufs: Float,
    /** Loudest 3 s short-term window, LUFS. */
    val maxShortTermLufs: Float,
    /** Loudest 400 ms momentary window, LUFS. */
    val maxMomentaryLufs: Float,
    /** EBU Tech 3342 loudness range (10th-95th short-term percentile), LU. */
    val loudnessRangeLu: Float,
    /** Highest sample value, dBFS. */
    val samplePeakDb: Float,
    /** Highest inter-sample value at 4x oversampling, dBTP. */
    val truePeakDb: Float
)

/**
 * Integrated loudness, short-term/momentary maxima and loudness range.
 *
 * Gating is the standard one: 400 ms blocks every 100 ms, an absolute gate at
 * -70 LUFS, then a relative gate 10 LU below the loudness of the blocks that
 * survived. One pass over the K-weighted signal fills every window length, so a
 * four-minute master costs a few tens of milliseconds on a phone.
 */
class LoudnessAnalyzer(private val sampleRate: Int) {

    fun analyze(interleaved: FloatArray, channels: Int = 2): LoudnessStats {
        val frames = interleaved.size / channels
        val momentaryBlocks = blockLoudness(interleaved, channels, frames, momentaryFrames)
        val shortTermBlocks = blockLoudness(interleaved, channels, frames, shortTermFrames)

        val integrated = gatedLoudness(momentaryBlocks, relativeGateLu = 10f)
        val shortTermGated = gatedBlocks(shortTermBlocks, relativeGateLu = 20f)

        val maxMomentary = momentaryBlocks.maxOrNull() ?: -60f
        val maxShortTerm = shortTermBlocks.maxOrNull() ?: -60f

        return LoudnessStats(
            integratedLufs = integrated,
            maxShortTermLufs = maxShortTerm,
            maxMomentaryLufs = maxMomentary,
            loudnessRangeLu = loudnessRange(shortTermGated),
            samplePeakDb = measureSamplePeakDb(interleaved),
            truePeakDb = measureTruePeakDb(interleaved, channels)
        )
    }

    private val momentaryFrames: Int get() = max(1, (0.400 * sampleRate).toInt())
    private val shortTermFrames: Int get() = max(1, (3.0 * sampleRate).toInt())
    private val momentaryStep: Int get() = max(1, (0.100 * sampleRate).toInt())
    private val shortTermStep: Int get() = max(1, sampleRate)

    /**
     * Per-block loudness in LUFS for every window of [blockFrames] advancing by
     * [stepFrames]. Window positions are computed on the K-weighted mean square
     * of both channels summed (BS.1770's `G_i` are 1.0 for L and R).
     */
    private fun blockLoudness(
        interleaved: FloatArray,
        channels: Int,
        frames: Int,
        blockFrames: Int
    ): FloatArray {
        if (frames <= 0) return FloatArray(0)
        val step = if (blockFrames == momentaryFrames) momentaryStep else shortTermStep
        if (frames < blockFrames) {
            // Shorter than one window: the whole file is the window.
            val z = weightedMeanSquare(interleaved, channels, 0, frames)
            return floatArrayOf(lufsFromMeanSquare(z))
        }
        val count = ((frames - blockFrames) / step) + 1
        val out = FloatArray(count)

        // Sliding sum of the weighted power over the window.
        var sum = 0.0
        var cursor = 0
        val shelfL = KWeighting.highShelf(sampleRate); val hpL = KWeighting.highPass(sampleRate)
        val shelfR = KWeighting.highShelf(sampleRate); val hpR = KWeighting.highPass(sampleRate)
        // Pre-compute the weighted power per frame once: windows then share it.
        val power = FloatArray(frames)
        var frame = 0
        var idx = 0
        while (frame < frames) {
            val wl = hpL.process(shelfL.process(interleaved[idx]))
            // A mono file counts its single channel; only a real second channel
            // adds energy (BS.1770 sums channel mean squares, G_i = 1).
            var powerSum = wl * wl
            if (channels > 1) {
                val wr = hpR.process(shelfR.process(interleaved[idx + 1]))
                powerSum += wr * wr
            }
            power[frame] = powerSum
            frame++
            idx += channels
        }

        // First window.
        for (i in 0 until blockFrames) sum += power[i]
        out[0] = lufsFromMeanSquare(sum / blockFrames)
        cursor = 0

        var blockIndex = 1
        while (blockIndex < count) {
            // Slide: drop `step` frames, add `step` frames. `count` guarantees
            // the incoming window never runs past the end of the file.
            for (i in 0 until step) sum -= power[cursor + i]
            val addStart = cursor + blockFrames
            val addEnd = min(frames, addStart + step)
            for (i in addStart until addEnd) sum += power[i]
            cursor += step
            out[blockIndex] = lufsFromMeanSquare(sum / blockFrames)
            blockIndex++
        }
        return out
    }

    private fun weightedMeanSquare(
        interleaved: FloatArray,
        channels: Int,
        from: Int,
        to: Int
    ): Double {
        val shelfL = KWeighting.highShelf(sampleRate); val hpL = KWeighting.highPass(sampleRate)
        val shelfR = KWeighting.highShelf(sampleRate); val hpR = KWeighting.highPass(sampleRate)
        var sum = 0.0
        var frame = from
        while (frame < to) {
            val idx = frame * channels
            val wl = hpL.process(shelfL.process(interleaved[idx]))
            sum += (wl * wl).toDouble()
            if (channels > 1) {
                val wr = hpR.process(shelfR.process(interleaved[idx + 1]))
                sum += (wr * wr).toDouble()
            }
            frame++
        }
        return sum / max(1, to - from)
    }

    /** Mean square -> LUFS: `-0.691 + 10 log10(z)`, floored at -60 for silence. */
    private fun lufsFromMeanSquare(meanSquare: Double): Float {
        if (meanSquare <= 1e-9) return -60f
        return (-0.691 + 10.0 * log10(meanSquare)).toFloat().coerceAtLeast(-60f)
    }

    /**
     * BS.1770 gating: absolute gate at -70 LUFS, then a relative gate
     * [relativeGateLu] below the loudness of the surviving blocks.
     */
    private fun gatedLoudness(blocks: FloatArray, relativeGateLu: Float): Float {
        val kept = gatedBlocks(blocks, relativeGateLu)
        if (kept.isEmpty()) return -60f
        // Work back from gate-reduced block loudness to mean square so the
        // average is over energy, not over decibels.
        var sum = 0.0
        for (v in kept) sum += 10.0.pow((v + 0.691) / 10.0)
        return lufsFromMeanSquare(sum / kept.size)
    }

    private fun gatedBlocks(blocks: FloatArray, relativeGateLu: Float): List<Float> {
        val aboveAbsolute = blocks.filter { it > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return emptyList()
        var sum = 0.0
        for (v in aboveAbsolute) sum += 10.0.pow((v + 0.691) / 10.0)
        val relativeLoudness = lufsFromMeanSquare(sum / aboveAbsolute.size)
        val threshold = relativeLoudness - relativeGateLu
        return aboveAbsolute.filter { it > threshold }
    }

    private fun loudnessRange(gated: List<Float>): Float {
        if (gated.size < 2) return 0f
        val sorted = gated.sorted()
        val low = sorted[((sorted.size - 1) * 0.10).toInt()]
        val high = sorted[((sorted.size - 1) * 0.95).toInt()]
        return (high - low).coerceAtLeast(0f)
    }

    fun measureSamplePeakDb(interleaved: FloatArray): Float {
        var peak = 0f
        for (v in interleaved) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        return linearToDb(peak)
    }

    /**
     * True peak by 4x oversampling with a windowed-sinc interpolator: the
     * standard's Table 3 filter in spirit (polyphase, unity passband gain),
     * sized for a phone. Validated against a 4x `resample_poly` reference in
     * `ReferenceAnalysisTest`.
     */
    fun measureTruePeakDb(interleaved: FloatArray, channels: Int = 2): Float {
        val frames = interleaved.size / channels
        if (frames == 0) return linearToDb(0f)
        var peak = 0f
        for (ch in 0 until channels) {
            peak = max(peak, polyphasePeak(interleaved, channels, ch, frames))
        }
        return linearToDb(peak)
    }

    private fun polyphasePeak(
        interleaved: FloatArray,
        channels: Int,
        channel: Int,
        frames: Int
    ): Float {
        val taps = TruePeakFilter.TAPS_PER_PHASE
        var peak = 0f
        for (n in 0 until frames) {
            val a = abs(interleaved[n * channels + channel])
            if (a > peak) peak = a
        }
        val history = FloatArray(taps)
        var historyCount = 0
        for (n in 0 until frames) {
            val x = interleaved[n * channels + channel]
            // Shift in the new sample (newest first).
            for (i in taps - 1 downTo 1) history[i] = history[i - 1]
            history[0] = x
            historyCount++
            if (historyCount < taps) continue
            for (phase in 0 until TruePeakFilter.OVERSAMPLE) {
                var acc = 0.0
                for (t in 0 until taps) acc += history[t] * TruePeakFilter.coeffs[phase][t]
                val v = abs(acc).toFloat()
                if (v > peak) peak = v
            }
        }
        return peak
    }

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70f
    }
}

/**
 * Polyphase interpolation coefficients for the true-peak meter: a
 * windowed-sinc low-pass split into [OVERSAMPLE] phases (Blackman window).
 */
internal object TruePeakFilter {
    const val OVERSAMPLE = 4

    /**
     * Taps per phase. 16 (a 64-tap prototype) measures real masters within
     * 0.01 dB of a `resample_poly` 4x reference and a worst-case quarter-rate
     * sine within 0.12 dB, which is well inside the standard's own tolerance.
     */
    const val TAPS_PER_PHASE = 16
    private const val TOTAL_TAPS = OVERSAMPLE * TAPS_PER_PHASE

    /**
     * Polyphase branches of a windowed-sinc interpolator.
     *
     * The prototype is centred on an exact multiple of [OVERSAMPLE] so phase 0
     * lands on a sample and the four phases cover the quarter positions between
     * samples; each branch sums to about 1, which is what keeps the interpolated
     * amplitude equal to the sampled one. (Getting either wrong measures the
     * sample peak and calls it a true peak.)
     */
    val coeffs: Array<DoubleArray> = Array(OVERSAMPLE) { phase ->
        DoubleArray(TAPS_PER_PHASE) { tap ->
            val n = phase + tap * OVERSAMPLE
            val centre = (OVERSAMPLE * (TAPS_PER_PHASE / 2)).toDouble()
            val x = n - centre
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x / OVERSAMPLE) / (PI * x / OVERSAMPLE)
            // Blackman window over the prototype length.
            val w = 0.42 - 0.5 * cos(2.0 * PI * n / (TOTAL_TAPS - 1)) +
                0.08 * cos(4.0 * PI * n / (TOTAL_TAPS - 1))
            sinc * w
        }
    }
}

internal fun linearToDb(v: Float): Float =
    if (v <= 1e-6f) -120f else (20.0 * log10(v.toDouble())).toFloat()

/** dBFS back to linear amplitude (the inverse of [linearToDb]). */
internal fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

// ---------------------------------------------------------------------------
// Tonal balance
// ---------------------------------------------------------------------------

/**
 * Loudness-independent tonal balance in octave bands.
 *
 * Each band is reported as dB relative to the summed power of all bands, so a
 * quiet reference and a loud one with the same balance read the same. That is
 * what makes "your mix is 2.4 dB hotter at 63 Hz than the reference" a
 * meaningful statement rather than a loudness artefact.
 */
class SpectrumBalanceAnalyzer(private val fftSize: Int = 4096) {

    fun analyze(interleaved: FloatArray, sampleRate: Int, channels: Int = 2): List<Float> {
        val frames = interleaved.size / channels
        if (frames < fftSize / 4) return List(BAND_CENTERS_HZ.size) { 0f }

        val window = FloatArray(fftSize) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
        }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val bandPower = DoubleArray(BAND_CENTERS_HZ.size)

        // Spot-sample the file: enough windows for a stable average, bounded
        // work for a ten-minute track.
        val maxWindows = SPOT_WINDOWS
        val hop = max(fftSize, frames / maxWindows)
        var start = 0
        var windows = 0
        while (start + fftSize <= frames && windows < maxWindows) {
            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            for (i in 0 until fftSize) {
                val idx = (start + i) * channels
                val l = interleaved[idx]
                val r = if (channels > 1) interleaved[idx + 1] else l
                real[i] = (l + r) * 0.5f * window[i]
            }
            fft(real, imag)
            val binHz = sampleRate.toDouble() / fftSize
            for (bin in 1 until fftSize / 2) {
                val hz = bin * binHz
                val band = bandFor(hz)
                if (band >= 0) {
                    val p = real[bin].toDouble() * real[bin] + imag[bin].toDouble() * imag[bin]
                    bandPower[band] += p
                }
            }
            start += hop
            windows++
        }

        val total = bandPower.sum()
        if (total <= 1e-12) return List(BAND_CENTERS_HZ.size) { 0f }
        return bandPower.map { p ->
            if (p <= 1e-12) -60f else (10.0 * log10(p / total)).toFloat()
        }
    }

    private fun bandFor(hz: Double): Int {
        for (i in BAND_CENTERS_HZ.indices) {
            val lower = BAND_CENTERS_HZ[i] / SQRT2
            val upper = BAND_CENTERS_HZ[i] * SQRT2
            if (hz >= lower && hz < upper) return i
        }
        return -1
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]; val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2]; val vIm = imag[i + k + len / 2]
                    val tRe = wr * vRe - wi * vIm
                    val tIm = wr * vIm + wi * vRe
                    real[i + k] = uRe + tRe
                    imag[i + k] = uIm + tIm
                    real[i + k + len / 2] = uRe - tRe
                    imag[i + k + len / 2] = uIm - tIm
                    val nwRe = wr * wRe - wi * wIm
                    wi = wr * wIm + wi * wRe
                    wr = nwRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** Octave centres, 31.5 Hz to 16 kHz. */
        val BAND_CENTERS_HZ = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val BAND_LABELS = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        private const val SQRT2 = 1.4142135623730951
        private const val SPOT_WINDOWS = 240
    }
}

/** Mid/side width in dB and L/R correlation in [-1, 1]. */
data class StereoImage(
    /** `20 log10(rms(side) / rms(mid))`; -60 dB for mono/centre-only material. */
    val widthDb: Float,
    /** Pearson correlation of L and R; +1 is mono, 0 uncorrelated, -1 anti-phase. */
    val correlation: Float
)

object StereoImageAnalyzer {
    fun analyze(interleaved: FloatArray, channels: Int = 2): StereoImage {
        val frames = interleaved.size / channels
        if (frames == 0 || channels < 2) return StereoImage(-60f, 1f)
        var sumMid = 0.0
        var sumSide = 0.0
        var sumLL = 0.0
        var sumRR = 0.0
        var sumLR = 0.0
        var i = 0
        while (i < frames) {
            val idx = i * channels
            val l = interleaved[idx].toDouble()
            val r = interleaved[idx + 1].toDouble()
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5
            sumMid += mid * mid
            sumSide += side * side
            sumLL += l * l
            sumRR += r * r
            sumLR += l * r
            i++
        }
        val width = if (sumMid <= 1e-12) -60f
        else (10.0 * log10(sumSide / sumMid)).toFloat().coerceAtLeast(-60f)
        val denom = sqrt(sumLL * sumRR)
        // A silent channel carries no correlation information: report 0
        // (uncorrelated) rather than pretending the pair is mono.
        val corr = if (denom <= 1e-12) 0f else (sumLR / denom).toFloat().coerceIn(-1f, 1f)
        return StereoImage(width, corr)
    }
}

// ---------------------------------------------------------------------------
// The profile
// ---------------------------------------------------------------------------

/**
 * Everything measured about one track, mix or reference. Immutable and
 * serialisable into the session so a saved project does not have to re-decode
 * a four-minute master to remember what it sounded like.
 */
data class ReferenceProfile(
    val label: String = "",
    val durationSeconds: Float = 0f,
    val sampleRate: Int = 48000,
    val integratedLufs: Float = -60f,
    val maxShortTermLufs: Float = -60f,
    val maxMomentaryLufs: Float = -60f,
    val loudnessRangeLu: Float = 0f,
    val samplePeakDb: Float = -120f,
    val truePeakDb: Float = -120f,
    /** Octave-band power relative to the total, dB. See [SpectrumBalanceAnalyzer]. */
    val bandBalanceDb: List<Float> = emptyList(),
    val widthDb: Float = -60f,
    val correlation: Float = 1f
) {
    /** Peak-to-loudness ratio: a cheap "how squashed is this" figure, in LU. */
    val crestFactorLu: Float get() = truePeakDb - integratedLufs

    val isSilent: Boolean get() = integratedLufs <= -59f
}

/**
 * One-shot profiler. `analyze` is deliberately synchronous and pure so tests
 * can run it on desktop JVM without an Android device, and so callers can put it
 * behind whatever dispatcher they like.
 */
/**
 * The reference a session carries: where the audio lives, how it is aligned to
 * the session timeline, and the measured profile so a reopened project can show
 * the comparison without decoding a four-minute master again.
 *
 * The audio is user-supplied and never bundled with the app; only the file the
 * user imported, copied into the app's own storage.
 */
data class ReferenceTrackConfig(
    val name: String = "",
    /** Absolute path of the imported copy inside the app's files directory. */
    val filePath: String = "",
    /** Frames into the reference that line up with the session's frame 0. */
    val offsetFrames: Long = 0L,
    /** Whether A/B audition was left on when the project was saved. */
    val auditionEnabled: Boolean = false,
    /** Gain applied to the reference so it matches the mix's loudness, dB. */
    val matchGainDb: Float = 0f,
    /** True once a reference tone match has been applied to the mix bus. */
    val toneMatchApplied: Boolean = false,
    val profile: ReferenceProfile = ReferenceProfile()
)

object ReferenceProfiler {

    fun analyze(
        interleaved: FloatArray,
        sampleRate: Int,
        label: String = "",
        channels: Int = 2
    ): ReferenceProfile {
        if (interleaved.isEmpty()) {
            return ReferenceProfile(label = label, sampleRate = sampleRate)
        }
        val loudness = LoudnessAnalyzer(sampleRate).analyze(interleaved, channels)
        val bands = SpectrumBalanceAnalyzer().analyze(interleaved, sampleRate, channels)
        val image = StereoImageAnalyzer.analyze(interleaved, channels)
        return ReferenceProfile(
            label = label,
            durationSeconds = interleaved.size / channels.toFloat() / sampleRate,
            sampleRate = sampleRate,
            integratedLufs = loudness.integratedLufs,
            maxShortTermLufs = loudness.maxShortTermLufs,
            maxMomentaryLufs = loudness.maxMomentaryLufs,
            loudnessRangeLu = loudness.loudnessRangeLu,
            samplePeakDb = loudness.samplePeakDb,
            truePeakDb = loudness.truePeakDb,
            bandBalanceDb = bands,
            widthDb = image.widthDb,
            correlation = image.correlation
        )
    }
}
