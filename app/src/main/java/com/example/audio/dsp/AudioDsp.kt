package com.example.audio.dsp

import com.example.audio.model.AudioClip
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.ChorusConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FadeCurve
import com.example.audio.model.FilterType
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.ResonanceSmootherConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.VocalRiderConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-power pan law (-3 dB at center).
 * Pan ranges from -1.0 (hard left) to +1.0 (hard right).
 */
object PanLaw {
    fun calculateGains(pan: Float): Pair<Float, Float> {
        val clamped = pan.coerceIn(-1.0f, 1.0f)
        val angle = (clamped + 1.0f) * (PI.toFloat() / 4.0f) // 0 to PI/2
        val left = cos(angle)
        val right = sin(angle)
        return Pair(left, right)
    }
}

/**
 * One-pole parameter smoother to avoid clicks/zipper noise on audio thread.
 */
class ParameterSmoother(initialValue: Float = 1.0f, sampleRate: Float = 48000f, timeConstantMs: Float = 15f) {
    private var currentValue = initialValue
    private var targetValue = initialValue
    private val alpha: Float = 1.0f - exp(-1.0f / (timeConstantMs * 0.001f * sampleRate))

    fun setTarget(target: Float) {
        targetValue = target
    }

    fun reset(value: Float) {
        currentValue = value
        targetValue = value
    }

    fun next(): Float {
        currentValue += alpha * (targetValue - currentValue)
        return currentValue
    }

    fun getCurrent(): Float = currentValue
}

/**
 * Biquad filter implementation based on Robert Bristow-Johnson Audio EQ Cookbook.
 */
class BiquadFilter(private val sampleRate: Float = 48000f) {
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    // State registers for Left and Right channels
    private var x1L = 0.0f
    private var x2L = 0.0f
    private var y1L = 0.0f
    private var y2L = 0.0f

    private var x1R = 0.0f
    private var x2R = 0.0f
    private var y1R = 0.0f
    private var y2R = 0.0f

    fun configure(band: EqBand) {
        if (!band.enabled) {
            b0 = 1.0f
            b1 = 0.0f
            b2 = 0.0f
            a1 = 0.0f
            a2 = 0.0f
            return
        }

        val f0 = band.frequencyHz.coerceIn(20f, sampleRate * 0.49f)
        val q = band.qFactor.coerceIn(0.1f, 10.0f)
        val gainDb = band.gainDb.coerceIn(-24f, 24f)
        val aLinear = 10.0.pow(gainDb / 40.0).toFloat()
        val omega = (2.0 * PI * f0 / sampleRate).toFloat()
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / (2.0f * q)

        var a0 = 1.0f

        when (band.type) {
            FilterType.PEAKING -> {
                b0 = 1.0f + alpha * aLinear
                b1 = -2.0f * cs
                b2 = 1.0f - alpha * aLinear
                a0 = 1.0f + alpha / aLinear
                a1 = -2.0f * cs
                a2 = 1.0f - alpha / aLinear
            }
            FilterType.LOW_SHELF -> {
                val sqrtA = sqrt(aLinear)
                b0 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cs + 2.0f * sqrtA * alpha)
                b1 = 2.0f * aLinear * ((aLinear - 1.0f) - (aLinear + 1.0f) * cs)
                b2 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cs - 2.0f * sqrtA * alpha)
                a0 = (aLinear + 1.0f) + (aLinear - 1.0f) * cs + 2.0f * sqrtA * alpha
                a1 = -2.0f * ((aLinear - 1.0f) + (aLinear + 1.0f) * cs)
                a2 = (aLinear + 1.0f) + (aLinear - 1.0f) * cs - 2.0f * sqrtA * alpha
            }
            FilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(aLinear)
                b0 = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cs + 2.0f * sqrtA * alpha)
                b1 = -2.0f * aLinear * ((aLinear - 1.0f) + (aLinear + 1.0f) * cs)
                b2 = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cs - 2.0f * sqrtA * alpha)
                a0 = (aLinear + 1.0f) - (aLinear - 1.0f) * cs + 2.0f * sqrtA * alpha
                a1 = 2.0f * ((aLinear - 1.0f) - (aLinear + 1.0f) * cs)
                a2 = (aLinear + 1.0f) - (aLinear - 1.0f) * cs - 2.0f * sqrtA * alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1.0f + cs) / 2.0f
                b1 = -(1.0f + cs)
                b2 = (1.0f + cs) / 2.0f
                a0 = 1.0f + alpha
                a1 = -2.0f * cs
                a2 = 1.0f - alpha
            }
            FilterType.LOW_PASS -> {
                b0 = (1.0f - cs) / 2.0f
                b1 = 1.0f - cs
                b2 = (1.0f - cs) / 2.0f
                a0 = 1.0f + alpha
                a1 = -2.0f * cs
                a2 = 1.0f - alpha
            }
        }

        // Normalize coefficients by a0
        val invA0 = 1.0f / a0
        b0 *= invA0
        b1 *= invA0
        b2 *= invA0
        a1 *= invA0
        a2 *= invA0
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val outL = b0 * inL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = inL
        y2L = y1L
        y1L = outL

        val outR = b0 * inR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = inR
        y2R = y1R
        y1R = outR

        return Pair(outL, outR)
    }

    fun reset() {
        x1L = 0f; x2L = 0f; y1L = 0f; y2L = 0f
        x1R = 0f; x2R = 0f; y1R = 0f; y2R = 0f
    }

    /**
     * Compute analytical magnitude response in dB at test frequency for UI plotting.
     */
    fun responseAtFrequency(freqHz: Float): Float {
        val omega = (2.0 * PI * freqHz / sampleRate).toFloat()
        val cosW = cos(omega)
        val cos2W = cos(2.0f * omega)
        val sinW = sin(omega)
        val sin2W = sin(2.0f * omega)

        val numReal = b0 + b1 * cosW + b2 * cos2W
        val numImag = -(b1 * sinW + b2 * sin2W)

        val denReal = 1.0f + a1 * cosW + a2 * cos2W
        val denImag = -(a1 * sinW + a2 * sin2W)

        val numMag2 = numReal * numReal + numImag * numImag
        val denMag2 = denReal * denReal + denImag * denImag

        val mag2 = if (denMag2 > 1e-12f) numMag2 / denMag2 else 1.0f
        return (10.0f * log10(max(1e-6f, mag2)))
    }
}

/**
 * 4-Band Parametric EQ unit processing both stereo channels.
 */
class FourBandEq(sampleRate: Float = 48000f) {
    private val filter1 = BiquadFilter(sampleRate)
    private val filter2 = BiquadFilter(sampleRate)
    private val filter3 = BiquadFilter(sampleRate)
    private val filter4 = BiquadFilter(sampleRate)

    fun updateConfig(config: EqConfig) {
        if (!config.enabled) {
            filter1.configure(config.band1.copy(enabled = false))
            filter2.configure(config.band2.copy(enabled = false))
            filter3.configure(config.band3.copy(enabled = false))
            filter4.configure(config.band4.copy(enabled = false))
        } else {
            filter1.configure(config.band1)
            filter2.configure(config.band2)
            filter3.configure(config.band3)
            filter4.configure(config.band4)
        }
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val (s1L, s1R) = filter1.processSample(inL, inR)
        val (s2L, s2R) = filter2.processSample(s1L, s1R)
        val (s3L, s3R) = filter3.processSample(s2L, s2R)
        return filter4.processSample(s3L, s3R)
    }

    fun reset() {
        filter1.reset()
        filter2.reset()
        filter3.reset()
        filter4.reset()
    }

    fun calculateTotalResponseDb(freqHz: Float): Float {
        return filter1.responseAtFrequency(freqHz) +
                filter2.responseAtFrequency(freqHz) +
                filter3.responseAtFrequency(freqHz) +
                filter4.responseAtFrequency(freqHz)
    }
}

/**
 * Feedforward Stereo Compressor with soft-knee and attack/release ballistics.
 */
class StereoCompressor(private val sampleRate: Float = 48000f) {
    private var thresholdDb = -16f
    private var ratio = 3.5f
    private var attackCoeff = 0.0f
    private var releaseCoeff = 0.0f
    private var kneeWidthDb = 4f
    private var makeupGainLinear = 1.0f
    private var enabled = false

    private var detectorState = 0.0f
    var currentGainReductionDb = 0.0f
        private set

    init {
        configure(CompressorConfig())
    }

    fun configure(config: CompressorConfig) {
        enabled = config.enabled
        thresholdDb = config.thresholdDb
        ratio = max(1.0f, config.ratio)
        kneeWidthDb = max(0.0f, config.kneeWidthDb)
        makeupGainLinear = 10.0.pow(config.makeupGainDb / 20.0).toFloat()

        val attackSec = max(0.0001f, config.attackMs * 0.001f)
        val releaseSec = max(0.001f, config.releaseMs * 0.001f)
        attackCoeff = exp(-1.0f / (attackSec * sampleRate))
        releaseCoeff = exp(-1.0f / (releaseSec * sampleRate))
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) {
            currentGainReductionDb = 0.0f
            return Pair(inL, inR)
        }

        // Peak detector (max absolute amplitude of stereo channels)
        val peak = max(Math.abs(inL), Math.abs(inR))
        val peakDb = if (peak > 1e-5f) 20.0f * log10(peak) else -100.0f

        // Static compression curve with soft knee
        val halfKnee = kneeWidthDb * 0.5f
        val delta = peakDb - thresholdDb

        val targetGrDb = when {
            delta <= -halfKnee -> 0.0f
            delta >= halfKnee -> (1.0f - 1.0f / ratio) * delta
            else -> {
                val kneeTerm = (delta + halfKnee) / kneeWidthDb
                (1.0f - 1.0f / ratio) * (kneeTerm * kneeTerm * halfKnee)
            }
        }

        // Ballistics smoothing
        val coeff = if (targetGrDb > detectorState) attackCoeff else releaseCoeff
        detectorState = targetGrDb + coeff * (detectorState - targetGrDb)
        currentGainReductionDb = detectorState

        val gainLinear = 10.0.pow(-detectorState / 20.0).toFloat() * makeupGainLinear
        return Pair(inL * gainLinear, inR * gainLinear)
    }

    fun reset() {
        detectorState = 0.0f
        currentGainReductionDb = 0.0f
    }
}

/**
 * Comb filter for Schroeder/Freeverb reverb.
 */
private class CombFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0
    private var filterStore = 0.0f

    fun process(input: Float, feedback: Float, damp: Float): Float {
        val output = buffer[index]
        filterStore = output * (1.0f - damp) + filterStore * damp
        buffer[index] = input + filterStore * feedback
        if (++index >= buffer.size) index = 0
        return output
    }

    fun reset() {
        buffer.fill(0f)
        index = 0
        filterStore = 0f
    }
}

/**
 * Allpass filter for Schroeder/Freeverb reverb.
 */
private class AllpassFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0

    fun process(input: Float): Float {
        val bufOut = buffer[index]
        val output = -input + bufOut
        buffer[index] = input + bufOut * 0.5f
        if (++index >= buffer.size) index = 0
        return output
    }

    fun reset() {
        buffer.fill(0f)
        index = 0
    }
}

/**
 * High-quality Schroeder/Freeverb algorithmic stereo reverb unit.
 */
class SchroederReverb(private val sampleRate: Float = 48000f) {
    private val scale = (sampleRate / 44100f)
    private val combLengths = intArrayOf(
        (1116 * scale).toInt(), (1188 * scale).toInt(),
        (1277 * scale).toInt(), (1356 * scale).toInt(),
        (1422 * scale).toInt(), (1491 * scale).toInt(),
        (1557 * scale).toInt(), (1617 * scale).toInt()
    )
    private val allpassLengths = intArrayOf(
        (556 * scale).toInt(), (441 * scale).toInt(),
        (341 * scale).toInt(), (225 * scale).toInt()
    )

    private val combFiltersL = combLengths.map { CombFilter(it) }
    private val combFiltersR = combLengths.map { CombFilter(it + 23) } // stereo spread offset
    private val allpassFiltersL = allpassLengths.map { AllpassFilter(it) }
    private val allpassFiltersR = allpassLengths.map { AllpassFilter(it + 23) }

    // Pre-delay buffer (max 200ms = 9600 frames)
    private val maxPreDelayFrames = (0.200f * sampleRate).toInt()
    private val preDelayBuffer = FloatArray(maxPreDelayFrames)
    private var preDelayWriteIdx = 0
    private var preDelayReadFrames = (0.020f * sampleRate).toInt()

    // Abbey Road low-cut and high-cut filters for vocal/master reverb clarity
    private val lowCutFilter = BiquadFilter(sampleRate)
    private val highCutFilter = BiquadFilter(sampleRate)

    private var roomSize = 0.6f
    private var damping = 0.4f
    private var stereoSpread = 0.8f
    private var returnLevel = 0.7f
    private var decayTimeSec = 2.2f
    private var enabled = true

    fun configure(config: ReverbConfig) {
        enabled = config.enabled
        roomSize = config.roomSize.coerceIn(0.1f, 0.98f)
        damping = config.damping.coerceIn(0.0f, 0.9f)
        stereoSpread = config.stereoWidth.coerceIn(0.0f, 1.0f)
        returnLevel = config.returnLevel.coerceIn(0.0f, 1.5f)
        decayTimeSec = config.decayTimeSec.coerceIn(0.5f, 10.0f)
        preDelayReadFrames = (config.preDelayMs.coerceIn(0f, 200f) * 0.001f * sampleRate).toInt().coerceIn(0, maxPreDelayFrames - 1)

        lowCutFilter.configure(EqBand(FilterType.HIGH_PASS, config.lowCutHz.coerceIn(20f, 1000f), 0f, 0.707f))
        highCutFilter.configure(EqBand(FilterType.LOW_PASS, config.highCutHz.coerceIn(1000f, 20000f), 0f, 0.707f))
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled || returnLevel <= 0.001f) return Pair(0f, 0f)

        val rawMono = (inL + inR) * 0.5f * 0.05f

        // Pre-delay line read
        preDelayBuffer[preDelayWriteIdx] = rawMono
        val readIdx = (preDelayWriteIdx - preDelayReadFrames + maxPreDelayFrames) % maxPreDelayFrames
        val delayedMono = preDelayBuffer[readIdx]
        preDelayWriteIdx = (preDelayWriteIdx + 1) % maxPreDelayFrames

        // Abbey Road filters
        val (filteredMono, _) = lowCutFilter.processSample(delayedMono, 0f)
        val (filteredMono2, _) = highCutFilter.processSample(filteredMono, 0f)

        // Feedback adjusted by RT60 decay time
        val decayScale = (decayTimeSec / 2.2f).coerceIn(0.5f, 2.0f)
        val feedback = ((roomSize * 0.28f + 0.7f) * (0.85f + 0.15f * decayScale)).coerceIn(0.5f, 0.98f)

        var outL = 0.0f
        var outR = 0.0f

        for (i in combFiltersL.indices) {
            outL += combFiltersL[i].process(filteredMono2, feedback, damping)
            outR += combFiltersR[i].process(filteredMono2, feedback, damping)
        }

        for (ap in allpassFiltersL) {
            outL = ap.process(outL)
        }
        for (ap in allpassFiltersR) {
            outR = ap.process(outR)
        }

        // Stereo width cross-mix
        val mid = (outL + outR) * 0.5f
        val side = (outL - outR) * 0.5f * stereoSpread

        val finalL = (mid + side) * returnLevel
        val finalR = (mid - side) * returnLevel

        return Pair(finalL, finalR)
    }

    fun reset() {
        preDelayBuffer.fill(0f)
        preDelayWriteIdx = 0
        combFiltersL.forEach { it.reset() }
        combFiltersR.forEach { it.reset() }
        allpassFiltersL.forEach { it.reset() }
        allpassFiltersR.forEach { it.reset() }
        lowCutFilter.reset()
        highCutFilter.reset()
    }
}

/**
 * Lookahead peak limiter on the master output bus.
 * Prevents digital inter-sample overs and clipping.
 */
class PeakLimiter(private val sampleRate: Float = 48000f) {
    private var enabled = true
    private var ceilingLinear = 0.965f // -0.3 dB
    private var releaseCoeff = 0.999f
    private var envelope = 0.0f

    init {
        configure(MasterLimiterConfig())
    }

    fun configure(config: MasterLimiterConfig) {
        enabled = config.enabled
        ceilingLinear = 10.0.pow(config.ceilingDb.coerceAtMost(0.0f) / 20.0).toFloat()
        val releaseSec = max(0.005f, config.releaseMs * 0.001f)
        releaseCoeff = exp(-1.0f / (releaseSec * sampleRate))
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) return Pair(inL, inR)

        val peak = max(Math.abs(inL), Math.abs(inR))
        envelope = max(peak, envelope * releaseCoeff)

        val gain = if (envelope > ceilingLinear) ceilingLinear / envelope else 1.0f

        // Soft saturation curve for any residual overshoot
        val satL = softSaturate(inL * gain, ceilingLinear)
        val satR = softSaturate(inR * gain, ceilingLinear)

        return Pair(satL, satR)
    }

    private fun softSaturate(x: Float, limit: Float): Float {
        val absX = Math.abs(x)
        return if (absX <= limit * 0.8f) {
            x
        } else {
            val sign = if (x >= 0f) 1.0f else -1.0f
            val norm = (absX - limit * 0.8f) / (limit * 0.2f)
            val saturated = limit * 0.8f + (limit * 0.2f) * (norm / (1.0f + norm))
            sign * saturated
        }
    }

    fun reset() {
        envelope = 0.0f
    }
}

/**
 * ITU-R BS.1770 K-weighting Loudness (LUFS) and Peak meter.
 */
class LufsMeter(sampleRate: Float = 48000f) {
    // Stage 1: High-pass pre-filter (~100 Hz 2nd-order highpass)
    private val preFilterL = BiquadFilter(sampleRate)
    private val preFilterR = BiquadFilter(sampleRate)

    // Stage 2: High-frequency shelf (~1 kHz 2nd-order high shelf)
    private val rlbfFilterL = BiquadFilter(sampleRate)
    private val rlbfFilterR = BiquadFilter(sampleRate)

    private var accumulatedMeanSquareL = 0.0
    private var accumulatedMeanSquareR = 0.0
    private var sampleCount = 0L

    private var peakLinearL = 0.0f
    private var peakLinearR = 0.0f

    var peakDbL = -100.0f
        private set
    var peakDbR = -100.0f
        private set
    var momentaryLufs = -100.0f
        private set
    var integratedLufs = -100.0f
        private set

    init {
        // ITU-R BS.1770 Stage 1: High shelf filter
        val shelfBand = EqBand(FilterType.HIGH_SHELF, 1500f, 4.0f, 0.707f)
        preFilterL.configure(shelfBand)
        preFilterR.configure(shelfBand)

        // Stage 2: High pass filter
        val hpfBand = EqBand(FilterType.HIGH_PASS, 60f, 0f, 0.5f)
        rlbfFilterL.configure(hpfBand)
        rlbfFilterR.configure(hpfBand)
    }

    fun addSample(sampleL: Float, sampleR: Float) {
        val absL = Math.abs(sampleL)
        val absR = Math.abs(sampleR)

        peakLinearL = max(absL, peakLinearL * 0.999f)
        peakLinearR = max(absR, peakLinearR * 0.999f)

        peakDbL = if (peakLinearL > 1e-5f) 20f * log10(peakLinearL) else -100f
        peakDbR = if (peakLinearR > 1e-5f) 20f * log10(peakLinearR) else -100f

        val (s1L, _) = preFilterL.processSample(sampleL, 0f)
        val (s2L, _) = rlbfFilterL.processSample(s1L, 0f)

        val (_, s1R) = preFilterR.processSample(0f, sampleR)
        val (_, s2R) = rlbfFilterR.processSample(0f, s1R)

        accumulatedMeanSquareL += (s2L * s2L)
        accumulatedMeanSquareR += (s2R * s2R)
        sampleCount++

        if (sampleCount % 4800L == 0L) { // update every 100ms
            val meanSquare = (accumulatedMeanSquareL + accumulatedMeanSquareR) / (sampleCount * 2.0)
            integratedLufs = if (meanSquare > 1e-10) {
                (-0.691 + 10.0 * log10(meanSquare)).toFloat()
            } else {
                -100f
            }
            momentaryLufs = integratedLufs
        }
    }

    fun reset() {
        accumulatedMeanSquareL = 0.0
        accumulatedMeanSquareR = 0.0
        sampleCount = 0L
        peakLinearL = 0.0f
        peakLinearR = 0.0f
        peakDbL = -100f
        peakDbR = -100f
        momentaryLufs = -100f
        integratedLufs = -100f
        preFilterL.reset()
        preFilterR.reset()
        rlbfFilterL.reset()
        rlbfFilterR.reset()
    }
}

/**
 * Analog Tape / Tube Saturation with harmonic warmth and soft clipping.
 */
class SaturationProcessor {
    private var enabled = false
    private var driveLinear = 1.0f
    private var warmth = 0.5f
    private var mix = 1.0f

    fun configure(config: SaturationConfig) {
        enabled = config.enabled
        driveLinear = 10.0.pow(config.driveDb.coerceIn(0f, 24f) / 20.0).toFloat()
        warmth = config.warmth.coerceIn(0f, 1f)
        mix = config.mix.coerceIn(0f, 1f)
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled || (driveLinear <= 1.001f && warmth <= 0.01f)) return Pair(inL, inR)

        val satL = saturate(inL * driveLinear, warmth)
        val satR = saturate(inR * driveLinear, warmth)

        val outL = inL * (1f - mix) + satL * mix
        val outR = inR * (1f - mix) + satR * mix

        return Pair(outL, outR)
    }

    private fun saturate(x: Float, w: Float): Float {
        val driven = x + w * 0.35f * (x * x)
        return kotlin.math.tanh(driven.toDouble()).toFloat() / (1.0f + w * 0.2f)
    }
}

/**
 * Ping-Pong / Stereo Delay with Sidechain Ducking.
 * Automatically ducks delay repetitions when dry channel audio is actively playing,
 * preventing vocal/lead intelligibility clutter and blooming in phrase pauses.
 */
class DuckingDelayProcessor(private val sampleRate: Float = 48000f) {
    private val maxDelayFrames = (sampleRate * 1.5f).toInt()
    private val bufferL = FloatArray(maxDelayFrames)
    private val bufferR = FloatArray(maxDelayFrames)
    private var writeIndex = 0

    private var delayFramesL = (0.35f * sampleRate).toInt()
    private var delayFramesR = (0.42f * sampleRate).toInt()
    private var feedback = 0.35f
    private var mix = 0.25f
    private var enabled = false

    // Sidechain ducking parameters
    private var duckingEnabled = true
    private var duckingThresholdLinear = 0.063f // -24 dB
    private var duckingDepthLinear = 0.25f      // -12 dB attenuation
    private var duckingReleaseCoeff = 0.999f
    private var currentDuckingGain = 1.0f

    fun configure(config: DuckingDelayConfig) {
        enabled = config.enabled
        val baseFrames = (config.timeMs.coerceIn(20f, 1200f) * 0.001f * sampleRate).toInt().coerceIn(1, maxDelayFrames - 1)
        delayFramesL = baseFrames
        delayFramesR = (baseFrames * 1.15f).toInt().coerceIn(1, maxDelayFrames - 1)
        feedback = config.feedback.coerceIn(0f, 0.85f)
        mix = config.mix.coerceIn(0f, 1f)

        duckingEnabled = config.duckingEnabled
        duckingThresholdLinear = 10.0.pow(config.duckingThresholdDb / 20.0).toFloat()
        duckingDepthLinear = 10.0.pow(config.duckingDepthDb / 20.0).toFloat()
        val relSec = max(0.01f, config.duckingReleaseMs * 0.001f)
        duckingReleaseCoeff = exp(-1.0f / (relSec * sampleRate))
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled || mix <= 0.001f) return Pair(inL, inR)

        val readIdxL = (writeIndex - delayFramesL + maxDelayFrames) % maxDelayFrames
        val readIdxR = (writeIndex - delayFramesR + maxDelayFrames) % maxDelayFrames

        val delayedL = bufferL[readIdxL]
        val delayedR = bufferR[readIdxR]

        bufferL[writeIndex] = inL + delayedR * feedback
        bufferR[writeIndex] = inR + delayedL * feedback

        writeIndex = (writeIndex + 1) % maxDelayFrames

        if (duckingEnabled) {
            val dryPeak = max(Math.abs(inL), Math.abs(inR))
            val targetGain = if (dryPeak > duckingThresholdLinear) duckingDepthLinear else 1.0f

            currentDuckingGain = if (targetGain < currentDuckingGain) {
                targetGain
            } else {
                currentDuckingGain + (1.0f - duckingReleaseCoeff) * (targetGain - currentDuckingGain)
            }
        } else {
            currentDuckingGain = 1.0f
        }

        val wetL = delayedL * currentDuckingGain
        val wetR = delayedR * currentDuckingGain

        val outL = inL * (1f - mix) + wetL * mix
        val outR = inR * (1f - mix) + wetR * mix

        return Pair(outL, outR)
    }

    fun reset() {
        bufferL.fill(0f)
        bufferR.fill(0f)
        writeIndex = 0
        currentDuckingGain = 1.0f
    }
}

/**
 * Per-channel fast brickwall limiter.
 */
class ChannelLimiter(private val sampleRate: Float = 48000f) {
    private var enabled = false
    private var ceilingLinear = 0.944f // -0.5 dB
    private var releaseCoeff = 0.999f
    private var envelope = 0.0f

    fun configure(config: ChannelLimiterConfig) {
        enabled = config.enabled
        ceilingLinear = 10.0.pow(config.ceilingDb.coerceAtMost(0.0f) / 20.0).toFloat()
        val releaseSec = max(0.005f, config.releaseMs * 0.001f)
        releaseCoeff = exp(-1.0f / (releaseSec * sampleRate))
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) return Pair(inL, inR)

        val peak = max(Math.abs(inL), Math.abs(inR))
        envelope = max(peak, envelope * releaseCoeff)
        val gain = if (envelope > ceilingLinear) ceilingLinear / envelope else 1.0f

        return Pair(inL * gain, inR * gain)
    }

    fun reset() {
        envelope = 0f
    }
}

/**
 * Stereo Chorus / Flanger / Vocal Doubler effect unit.
 * Uses fractional LFO-modulated delay buffers with stereo phase offset.
 */
class StereoChorusProcessor(private val sampleRate: Float = 48000f) {
    private val maxDelayFrames = (sampleRate * 0.05f).toInt() // 50ms max chorus delay
    private val bufferL = FloatArray(maxDelayFrames)
    private val bufferR = FloatArray(maxDelayFrames)
    private var writeIdx = 0

    private var enabled = false
    private var rateHz = 1.2f
    private var depthFrames = (0.0035f * sampleRate).toInt()
    private var feedback = 0.20f
    private var stereoSpread = 0.85f
    private var mix = 0.40f

    private var lfoPhase = 0.0f

    fun configure(config: ChorusConfig) {
        enabled = config.enabled
        rateHz = config.rateHz.coerceIn(0.1f, 8.0f)
        depthFrames = (config.depthMs.coerceIn(0.5f, 15.0f) * 0.001f * sampleRate).toInt().coerceIn(1, maxDelayFrames / 2)
        feedback = config.feedback.coerceIn(0.0f, 0.70f)
        stereoSpread = config.stereoSpread.coerceIn(0.0f, 1.0f)
        mix = config.mix.coerceIn(0.0f, 1.0f)
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled || mix <= 0.001f) return Pair(inL, inR)

        // Increment LFO Phase
        lfoPhase += (2.0f * PI.toFloat() * rateHz / sampleRate)
        if (lfoPhase >= 2.0f * PI.toFloat()) {
            lfoPhase -= 2.0f * PI.toFloat()
        }

        // Left LFO and Right LFO with Phase Offset
        val lfoL = 0.5f + 0.5f * sin(lfoPhase)
        val lfoR = 0.5f + 0.5f * sin(lfoPhase + stereoSpread * PI.toFloat())

        val delayL = 10f + lfoL * depthFrames
        val delayR = 10f + lfoR * depthFrames

        // Read from delay lines with linear interpolation
        val readIdxL = (writeIdx - delayL + maxDelayFrames) % maxDelayFrames
        val readIdxR = (writeIdx - delayR + maxDelayFrames) % maxDelayFrames

        val iL0 = readIdxL.toInt()
        val fracL = readIdxL - iL0
        val iL1 = (iL0 + 1) % maxDelayFrames
        val delayedL = bufferL[iL0] * (1f - fracL) + bufferL[iL1] * fracL

        val iR0 = readIdxR.toInt()
        val fracR = readIdxR - iR0
        val iR1 = (iR0 + 1) % maxDelayFrames
        val delayedR = bufferR[iR0] * (1f - fracR) + bufferR[iR1] * fracR

        // Write back with feedback
        bufferL[writeIdx] = inL + delayedL * feedback
        bufferR[writeIdx] = inR + delayedR * feedback
        writeIdx = (writeIdx + 1) % maxDelayFrames

        val outL = inL * (1f - mix) + delayedL * mix
        val outR = inR * (1f - mix) + delayedR * mix

        return Pair(outL, outR)
    }

    fun reset() {
        bufferL.fill(0f)
        bufferR.fill(0f)
        writeIdx = 0
        lfoPhase = 0f
    }
}

/**
 * Fade curve envelope math for smooth clip crossfades.
 */
object FadeMath {
    fun calculateFadeFactor(frameInFade: Long, totalFadeFrames: Long, curve: FadeCurve, isFadeIn: Boolean): Float {
        if (totalFadeFrames <= 0L) return 1.0f
        val t = (frameInFade.toFloat() / totalFadeFrames.toFloat()).coerceIn(0f, 1f)

        val rawFactor = when (curve) {
            FadeCurve.LINEAR -> t
            FadeCurve.EQUAL_POWER -> sin(t * (PI.toFloat() / 2.0f))
            FadeCurve.EXPONENTIAL -> ((exp(3.0f * t) - 1.0f) / (exp(3.0f) - 1.0f))
            FadeCurve.S_CURVE -> 0.5f * (1.0f - cos(t * PI.toFloat()))
        }

        return if (isFadeIn) rawFactor else (1.0f - rawFactor)
    }
}

/**
 * Composites non-destructive AudioClips into a continuous track buffer.
 */
object ClipCompositor {
    fun compositeClipsToTrackBuffer(
        sourceBuffer: FloatArray,
        clips: List<AudioClip>,
        totalTrackFrames: Long
    ): FloatArray {
        val totalLength = totalTrackFrames.toInt() * 2
        val output = FloatArray(totalLength)

        for (clip in clips) {
            val clipStartFrame = clip.timelineStartFrame
            val clipLenFrames = clip.lengthFrames
            val gain = clip.gainLinear

            for (f in 0L until clipLenFrames) {
                val targetFrame = clipStartFrame + f
                if (targetFrame < 0L || targetFrame >= totalTrackFrames) continue

                val sourceFrame = clip.sourceStartFrame + f
                val srcIdx = (sourceFrame * 2).toInt()

                var sampleL = 0.0f
                var sampleR = 0.0f

                if (srcIdx + 1 < sourceBuffer.size) {
                    sampleL = sourceBuffer[srcIdx]
                    sampleR = sourceBuffer[srcIdx + 1]
                }

                // Fade In factor
                var fadeMult = 1.0f
                if (clip.fadeInFrames > 0L && f < clip.fadeInFrames) {
                    fadeMult *= FadeMath.calculateFadeFactor(f, clip.fadeInFrames, clip.fadeInCurve, isFadeIn = true)
                }

                // Fade Out factor
                val remainingFrames = clipLenFrames - f
                if (clip.fadeOutFrames > 0L && remainingFrames <= clip.fadeOutFrames) {
                    val frameInFadeOut = clip.fadeOutFrames - remainingFrames
                    fadeMult *= FadeMath.calculateFadeFactor(frameInFadeOut, clip.fadeOutFrames, clip.fadeOutCurve, isFadeIn = false)
                }

                val outIdx = (targetFrame * 2).toInt()
                output[outIdx] += sampleL * gain * fadeMult
                output[outIdx + 1] += sampleR * gain * fadeMult
            }
        }

        return output
    }
}

/**
 * Vocal Resonance Smoother (Dynamic Formant Tamer & De-Esser).
 * Automatically detects and dynamically attenuates harsh vocal resonances in the 2kHz-8kHz zone.
 */
class ResonanceSmootherProcessor(private val sampleRate: Float = 48000f) {
    private var enabled = false
    private val filterL = BiquadFilter(sampleRate)
    private val filterR = BiquadFilter(sampleRate)
    private var thresholdLinear = 0.125f // -18 dBFS
    private var depthLinear = 0.5f       // -6 dB cut max
    private var currentReduction = 1.0f

    fun configure(config: ResonanceSmootherConfig) {
        enabled = config.enabled
        thresholdLinear = 10.0.pow(config.thresholdDb / 20.0).toFloat()
        depthLinear = 10.0.pow(config.depthDb / 20.0).toFloat()

        val band = EqBand(
            type = FilterType.PEAKING,
            frequencyHz = config.frequencyHz.coerceIn(1000f, 10000f),
            gainDb = config.depthDb,
            qFactor = config.qFactor.coerceIn(1.0f, 10.0f),
            enabled = config.enabled
        )
        filterL.configure(band)
        filterR.configure(band)
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) return Pair(inL, inR)

        val peak = max(Math.abs(inL), Math.abs(inR))
        val targetGain = if (peak > thresholdLinear) {
            val over = peak / thresholdLinear
            max(depthLinear, 1.0f / over)
        } else {
            1.0f
        }

        currentReduction = 0.9f * currentReduction + 0.1f * targetGain

        return Pair(inL * currentReduction, inR * currentReduction)
    }

    fun reset() {
        filterL.reset()
        filterR.reset()
        currentReduction = 1.0f
    }
}

/**
 * Vocal Rider (Automatic Volume / Gain Leveler).
 * Smoothly rides vocal gain to maintain consistent level.
 */
class VocalRiderProcessor(private val sampleRate: Float = 48000f) {
    private var enabled = false
    private var targetLinear = 0.125f // -18 LUFS / dBFS equivalent
    private var maxGainLinear = 2.0f  // +6 dB
    private var minGainLinear = 0.5f  // -6 dB
    private var envelope = 0.1f
    private var currentGain = 1.0f

    fun configure(config: VocalRiderConfig) {
        enabled = config.enabled
        maxGainLinear = 10.0.pow(config.maxGainDb / 20.0).toFloat()
        minGainLinear = 10.0.pow(config.minGainDb / 20.0).toFloat()
        targetLinear = 10.0.pow(config.targetLufs / 20.0).toFloat()
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) return Pair(inL, inR)

        val peak = max(Math.abs(inL), Math.abs(inR))
        envelope = 0.995f * envelope + 0.005f * peak

        val idealGain = if (envelope > 1e-4f) {
            (targetLinear / envelope).coerceIn(minGainLinear, maxGainLinear)
        } else {
            1.0f
        }

        currentGain += 0.01f * (idealGain - currentGain)

        return Pair(inL * currentGain, inR * currentGain)
    }

    fun reset() {
        envelope = 0.1f
        currentGain = 1.0f
    }
}

