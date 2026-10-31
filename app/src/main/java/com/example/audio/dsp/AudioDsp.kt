package com.example.audio.dsp

import com.example.audio.model.AudioClip
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FadeCurve
import com.example.audio.model.FilterType
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.ChorusConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.ResonanceSmootherConfig
import com.example.audio.model.VocalRiderConfig
import kotlin.math.*

// ============================================================
// BiquadFilter
// ============================================================
class BiquadFilter(
    private val sampleRate: Float,
    var b0: Float = 1f, var b1: Float = 0f, var b2: Float = 0f,
    var a1: Float = 0f, var a2: Float = 0f
) {
    private var x1L = 0f; private var x2L = 0f; private var y1L = 0f; private var y2L = 0f
    private var x1R = 0f; private var x2R = 0f; private var y1R = 0f; private var y2R = 0f

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        val outL = b0 * inL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L; x1L = inL; y2L = y1L; y1L = outL
        val outR = b0 * inR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R; x1R = inR; y2R = y1R; y1R = outR
        out[0] = outL; out[1] = outR
    }
    fun configure(band: EqBand) {
        val w0 = 2.0 * Math.PI * band.frequencyHz / sampleRate
        val cosW0 = cos(w0).toFloat(); val sinW0 = sin(w0).toFloat()
        val A = 10.0.pow(band.gainDb / 40.0).toFloat()
        val alpha = sinW0 / (2f * band.qFactor)
        when (band.type) {
            FilterType.HIGH_PASS -> {
                val a0 = 1f + alpha
                b0 = ((1f + cosW0) / 2f) / a0; b1 = (-(1f + cosW0)) / a0; b2 = ((1f + cosW0) / 2f) / a0
                a1 = (-2f * cosW0) / a0; a2 = (1f - alpha) / a0
            }
            FilterType.LOW_PASS -> {
                val a0 = 1f + alpha
                b0 = ((1f - cosW0) / 2f) / a0; b1 = (1f - cosW0) / a0; b2 = ((1f - cosW0) / 2f) / a0
                a1 = (-2f * cosW0) / a0; a2 = (1f - alpha) / a0
            }
            FilterType.LOW_SHELF -> {
                val a0 = (A + 1f) + (A - 1f) * cosW0 + 2f * sqrt(A) * alpha
                b0 = A * ((A + 1f) - (A - 1f) * cosW0 + 2f * sqrt(A) * alpha) / a0
                b1 = 2f * A * ((A - 1f) - (A + 1f) * cosW0) / a0
                b2 = A * ((A + 1f) - (A - 1f) * cosW0 - 2f * sqrt(A) * alpha) / a0
                a1 = -2f * ((A - 1f) + (A + 1f) * cosW0) / a0
                a2 = ((A + 1f) + (A - 1f) * cosW0 - 2f * sqrt(A) * alpha) / a0
            }
            FilterType.PEAKING -> {
                val a0 = 1f + alpha / A
                b0 = (1f + alpha * A) / a0; b1 = (-2f * cosW0) / a0; b2 = (1f - alpha * A) / a0
                a1 = (-2f * cosW0) / a0; a2 = (1f - alpha / A) / a0
            }
            FilterType.HIGH_SHELF -> {
                val a0 = (A + 1f) - (A - 1f) * cosW0 + 2f * sqrt(A) * alpha
                b0 = A * ((A + 1f) + (A - 1f) * cosW0 + 2f * sqrt(A) * alpha) / a0
                b1 = -2f * A * ((A - 1f) + (A + 1f) * cosW0) / a0
                b2 = A * ((A + 1f) + (A - 1f) * cosW0 - 2f * sqrt(A) * alpha) / a0
                a1 = 2f * ((A - 1f) - (A + 1f) * cosW0) / a0
                a2 = ((A + 1f) - (A - 1f) * cosW0 - 2f * sqrt(A) * alpha) / a0
            }
        }
        if (!band.enabled) { b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f }
    }
    fun responseAtFrequency(freq: Float): Float {
        val w = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w).toFloat(); val sinW = sin(w).toFloat()
        val cos2W = cos(2.0 * w).toFloat(); val sin2W = sin(2.0 * w).toFloat()
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = b1 * sinW + b2 * sin2W
        val denRe = 1f + a1 * cosW + a2 * cos2W
        val denIm = a1 * sinW + a2 * sin2W
        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)
        val mag = if (denMag > 1e-10f) numMag / denMag else 1f
        return 20f * log10(mag.coerceAtLeast(1e-10f))
    }
    fun reset() { x1L=0f;x2L=0f;y1L=0f;y2L=0f;x1R=0f;x2R=0f;y1R=0f;y2R=0f }
}

// ============================================================
// FourBandEq
// ============================================================
class FourBandEq(sampleRate: Float = 48000f) {
    private val highPassFilter = BiquadFilter(sampleRate)
    private val lowPassFilter = BiquadFilter(sampleRate)
    private val filter1 = BiquadFilter(sampleRate)
    private val filter2 = BiquadFilter(sampleRate)
    private val filter3 = BiquadFilter(sampleRate)
    private val filter4 = BiquadFilter(sampleRate)
    private var eqConfig: EqConfig? = null
    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        highPassFilter.processSampleInto(inL, inR, out)
        lowPassFilter.processSampleInto(out[0], out[1], out)
        filter1.processSampleInto(out[0], out[1], out)
        filter2.processSampleInto(out[0], out[1], out)
        filter3.processSampleInto(out[0], out[1], out)
        filter4.processSampleInto(out[0], out[1], out)
    }
    fun updateConfig(config: EqConfig) {
        eqConfig = config
        if (!config.enabled) {
            highPassFilter.configure(EqBand(enabled = false))
            lowPassFilter.configure(EqBand(enabled = false))
            filter1.configure(EqBand(enabled = false))
            filter2.configure(EqBand(enabled = false))
            filter3.configure(EqBand(enabled = false))
            filter4.configure(EqBand(enabled = false))
        } else {
            highPassFilter.configure(config.highPass)
            lowPassFilter.configure(config.lowPass)
            filter1.configure(config.band1)
            filter2.configure(config.band2)
            filter3.configure(config.band3)
            filter4.configure(config.band4)
        }
    }
    fun calculateTotalResponseDb(freq: Float): Float {
        return highPassFilter.responseAtFrequency(freq) + lowPassFilter.responseAtFrequency(freq) +
               filter1.responseAtFrequency(freq) + filter2.responseAtFrequency(freq) +
               filter3.responseAtFrequency(freq) + filter4.responseAtFrequency(freq)
    }
    fun reset() {
        highPassFilter.reset(); lowPassFilter.reset()
        filter1.reset(); filter2.reset(); filter3.reset(); filter4.reset()
    }
}

// ============================================================
// StereoCompressor
// ============================================================
class StereoCompressor(private val sampleRate: Float) {
    var enabled = true
    var thresholdDb = -18f
    var ratio = 2f
    var attackMs = 10f
    var releaseMs = 100f
    var makeupGainDb = 0f
    var kneeWidthDb = 3f
    var type = com.example.audio.model.CompressorType.VCA
    private var detectorState = 0f
    private var attackCoeff = exp(-1.0 / (10.0 * sampleRate / 1000.0)).toFloat()
    private var releaseCoeff = exp(-1.0 / (100.0 * sampleRate / 1000.0)).toFloat()
    var currentGainReductionDb = 0f
    private val makeupGainLinear get() = 10.0.pow(makeupGainDb / 20.0).toFloat()

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { currentGainReductionDb = 0f; out[0] = inL; out[1] = inR; return }
        val peak = max(abs(inL), abs(inR))
        val peakDb = if (peak > 1e-5f) 20f * log10(peak) else -100f
        val halfKnee = kneeWidthDb * 0.5f
        val delta = peakDb - thresholdDb
        val targetGrDb = when {
            delta <= -halfKnee -> 0f
            delta >= halfKnee -> (1f - 1f / ratio) * delta
            else -> { val kneeTerm = (delta + halfKnee) / kneeWidthDb; (1f - 1f / ratio) * (kneeTerm * kneeTerm * halfKnee) }
        }
        val coeff = if (targetGrDb > detectorState) attackCoeff else releaseCoeff
        detectorState = targetGrDb + coeff * (detectorState - targetGrDb)
        currentGainReductionDb = detectorState
        val gainLinear = 10.0.pow(-detectorState / 20.0).toFloat() * makeupGainLinear
        out[0] = inL * gainLinear; out[1] = inR * gainLinear
    }
    fun configure(config: CompressorConfig) {
        enabled = config.enabled
        type = config.type
        thresholdDb = config.thresholdDb
        ratio = config.ratio
        attackMs = config.attackMs
        releaseMs = config.releaseMs
        // The circuit model owns the knee shape. This keeps old sessions that
        // only stored a knee value consistent with their selected compressor.
        kneeWidthDb = when (type) {
            com.example.audio.model.CompressorType.VCA -> config.kneeWidthDb
            com.example.audio.model.CompressorType.LA_2A -> config.kneeWidthDb.coerceAtLeast(6f)
            com.example.audio.model.CompressorType.FET_1176 -> 0f
            com.example.audio.model.CompressorType.TUBE_TECH -> config.kneeWidthDb.coerceAtLeast(6f)
        }
        makeupGainDb = config.makeupGainDb
        attackCoeff = kotlin.math.exp(-1.0 / (attackMs.coerceAtLeast(0.05f) * sampleRate / 1000.0)).toFloat()
        releaseCoeff = kotlin.math.exp(-1.0 / (releaseMs.coerceAtLeast(1f) * sampleRate / 1000.0)).toFloat()
    }
    fun reset() { detectorState = 0f; currentGainReductionDb = 0f }
}

// ============================================================
// StereoChorusProcessor
// ============================================================
class StereoChorusProcessor(private val sampleRate: Float) {
    var enabled = true
    var mix = 0.5f
    var rateHz = 1.5f
    var depthMs = 5f
    var feedback = 0.3f
    var stereoSpread = 0.5f
    private val maxDelayFrames = (sampleRate * 0.05f).toInt() + 10
    private val bufferL = FloatArray(maxDelayFrames)
    private val bufferR = FloatArray(maxDelayFrames)
    private var writeIdx = 0
    private var lfoPhase = 0f
    private val depthFrames = depthMs * sampleRate / 1000f

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled || mix <= 0.001f) { out[0] = inL; out[1] = inR; return }
        lfoPhase += (2f * PI.toFloat() * rateHz / sampleRate)
        if (lfoPhase >= 2f * PI.toFloat()) lfoPhase -= 2f * PI.toFloat()
        val lfoL = 0.5f + 0.5f * sin(lfoPhase)
        val lfoR = 0.5f + 0.5f * sin(lfoPhase + stereoSpread * PI.toFloat())
        val delayL = 10f + lfoL * depthFrames
        val delayR = 10f + lfoR * depthFrames
        val readIdxL = (writeIdx - delayL + maxDelayFrames) % maxDelayFrames
        val readIdxR = (writeIdx - delayR + maxDelayFrames) % maxDelayFrames
        val iL0 = readIdxL.toInt(); val fracL = readIdxL - iL0; val iL1 = (iL0 + 1) % maxDelayFrames
        val delayedL = bufferL[iL0] * (1f - fracL) + bufferL[iL1] * fracL
        val iR0 = readIdxR.toInt(); val fracR = readIdxR - iR0; val iR1 = (iR0 + 1) % maxDelayFrames
        val delayedR = bufferR[iR0] * (1f - fracR) + bufferR[iR1] * fracR
        bufferL[writeIdx] = inL + delayedL * feedback
        bufferR[writeIdx] = inR + delayedR * feedback
        writeIdx = (writeIdx + 1) % maxDelayFrames
        out[0] = inL * (1f - mix) + delayedL * mix
        out[1] = inR * (1f - mix) + delayedR * mix
    }
    fun configure(config: ChorusConfig) {
        enabled = config.enabled; mix = config.mix; rateHz = config.rateHz
        depthMs = config.depthMs; feedback = config.feedback; stereoSpread = config.stereoSpread
    }
    fun reset() { writeIdx = 0; lfoPhase = 0f; bufferL.fill(0f); bufferR.fill(0f) }
}

// ============================================================
// DuckingDelayProcessor
// ============================================================
class DuckingDelayProcessor(private val sampleRate: Float) {
    var enabled = true
    var mix = 0.3f
    var delayMsL = 250f
    var delayMsR = 375f
    var feedback = 0.4f
    var duckingEnabled = true
    var duckingThresholdDb = -20f
    var duckingDepthDb = -12f
    var duckingReleaseMs = 200f
    // Sized for the documented 50–1000 ms range plus the 1.5x right-channel
    // offset, so turning the Time knob past 500 ms cannot write out of bounds.
    private val maxDelayFrames = (sampleRate * 1.6f).toInt() + 10
    private val bufferL = FloatArray(maxDelayFrames)
    private val bufferR = FloatArray(maxDelayFrames)
    private var writeIndex = 0
    private var delayFramesL = (delayMsL * sampleRate / 1000f).toInt()
    private var delayFramesR = (delayMsR * sampleRate / 1000f).toInt()
    private var currentDuckingGain = 1f
    private var duckingThresholdLinear = 10.0.pow(duckingThresholdDb / 20.0).toFloat()
    private var duckingDepthLinear = 10.0.pow(duckingDepthDb / 20.0).toFloat()
    private var duckingReleaseCoeff = exp(-1.0 / (duckingReleaseMs * sampleRate / 1000.0)).toFloat()

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled || mix <= 0.001f) { out[0] = inL; out[1] = inR; return }
        val readIdxL = (writeIndex - delayFramesL + maxDelayFrames) % maxDelayFrames
        val readIdxR = (writeIndex - delayFramesR + maxDelayFrames) % maxDelayFrames
        val delayedL = bufferL[readIdxL]
        val delayedR = bufferR[readIdxR]
        bufferL[writeIndex] = inL + delayedR * feedback
        bufferR[writeIndex] = inR + delayedL * feedback
        writeIndex = (writeIndex + 1) % maxDelayFrames
        if (duckingEnabled) {
            val dryPeak = max(abs(inL), abs(inR))
            val targetGain = if (dryPeak > duckingThresholdLinear) duckingDepthLinear else 1f
            currentDuckingGain = if (targetGain < currentDuckingGain) targetGain
                else currentDuckingGain + (1f - duckingReleaseCoeff) * (targetGain - currentDuckingGain)
        } else currentDuckingGain = 1f
        val wetL = delayedL * currentDuckingGain
        val wetR = delayedR * currentDuckingGain
        out[0] = inL * (1f - mix) + wetL * mix
        out[1] = inR * (1f - mix) + wetR * mix
    }
    fun configure(config: DuckingDelayConfig) {
        enabled = config.enabled; mix = config.mix; feedback = config.feedback
        duckingEnabled = config.duckingEnabled; duckingThresholdDb = config.duckingThresholdDb
        duckingDepthDb = config.duckingDepthDb
        duckingReleaseMs = config.duckingReleaseMs
        // These were computed once from the field defaults, so Time and the
        // ducking controls changed the model and the UI but never the audio.
        delayMsL = config.timeMs.coerceIn(1f, 1000f)
        delayMsR = (delayMsL * 1.5f).coerceAtMost(1500f)
        delayFramesL = (delayMsL * sampleRate / 1000f).toInt().coerceIn(1, maxDelayFrames - 1)
        delayFramesR = (delayMsR * sampleRate / 1000f).toInt().coerceIn(1, maxDelayFrames - 1)
        duckingThresholdLinear = 10.0.pow(duckingThresholdDb / 20.0).toFloat()
        duckingDepthLinear = 10.0.pow(duckingDepthDb / 20.0).toFloat()
        duckingReleaseCoeff = exp(-1.0 / (duckingReleaseMs.coerceAtLeast(1f) * sampleRate / 1000.0)).toFloat()
    }
    fun reset() { writeIndex = 0; currentDuckingGain = 1f; bufferL.fill(0f); bufferR.fill(0f) }
}

// ============================================================
// SaturationProcessor
// ============================================================
class SaturationProcessor {
    var enabled = true
    var driveLinear = 1f
    var warmth = 0f
    var mix = 0.5f
    var type = com.example.audio.model.SaturationType.TAPE
    private var tapeLpL = 0f
    private var tapeLpR = 0f
    private var ironLpL = 0f
    private var ironLpR = 0f
    private val tapeAlpha = 0.35f
    private val ironAlpha = 0.06f

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled || (driveLinear <= 1.001f && warmth <= 0.01f)) { out[0] = inL; out[1] = inR; return }
        val satL = saturate(inL * driveLinear, 0)
        val satR = saturate(inR * driveLinear, 1)
        val trim = 1f / (1f + (driveLinear - 1f) * 0.18f)
        out[0] = (inL * (1f - mix) + satL * trim * mix).coerceIn(-1f, 1f)
        out[1] = (inR * (1f - mix) + satR * trim * mix).coerceIn(-1f, 1f)
    }

    private fun saturate(x: Float, channel: Int): Float = when (type) {
        com.example.audio.model.SaturationType.TAPE -> {
            // Gentle symmetric tape compression with a little high-frequency loss.
            val driven = kotlin.math.tanh(x.toDouble()).toFloat()
            val lp = if (channel == 0) {
                tapeLpL += tapeAlpha * (driven - tapeLpL); tapeLpL
            } else {
                tapeLpR += tapeAlpha * (driven - tapeLpR); tapeLpR
            }
            val softened = driven * (1f - warmth * 0.18f) + lp * (warmth * 0.18f)
            softened + warmth * 0.035f * (driven * driven - 0.33f)
        }
        com.example.audio.model.SaturationType.TUBE -> {
            // Asymmetric transfer curve generates even-order harmonics.
            val bias = 1f + warmth * 0.55f
            val driven = x * (1f + warmth * 0.25f)
            val shaped = if (driven >= 0f) kotlin.math.tanh(driven.toDouble() * bias).toFloat()
                else kotlin.math.tanh(driven.toDouble() / bias).toFloat()
            shaped - warmth * 0.02f
        }
        com.example.audio.model.SaturationType.TRANSFORMER -> {
            // Iron core: soft cubic clip with low-mid weight.
            val driven = x / (1f + kotlin.math.abs(x) * (0.8f - warmth * 0.4f))
            val weight = if (channel == 0) {
                ironLpL += ironAlpha * (driven - ironLpL); ironLpL
            } else {
                ironLpR += ironAlpha * (driven - ironLpR); ironLpR
            }
            driven + weight * warmth * 0.55f
        }
        com.example.audio.model.SaturationType.CONSOLE -> {
            // Tight op-amp clip: harder knee, minimal low-end smear.
            val driven = kotlin.math.tanh(x.toDouble() * 1.35).toFloat()
            driven * (1f - warmth * 0.10f) + driven * driven * driven * warmth * 0.10f
        }
    }

    fun configure(config: SaturationConfig) {
        enabled = config.enabled
        driveLinear = 10.0.pow(config.driveDb / 20.0).toFloat()
        warmth = config.warmth.coerceIn(0f, 1f)
        mix = config.mix.coerceIn(0f, 1f)
        type = config.type
    }
    fun reset() { tapeLpL = 0f; tapeLpR = 0f; ironLpL = 0f; ironLpR = 0f }
}

// ============================================================
// LufsMeter
// ============================================================
class LufsMeter(private val sampleRate: Float) {
    private var sumSqL = 0f
    private var sumSqR = 0f
    private var count = 0
    var integratedLufs = -60f
    var momentaryLufs = -60f
    var peakDbL = -60f
    var peakDbR = -60f
    fun addSample(l: Float, r: Float) {
        sumSqL += l * l; sumSqR += r * r; count++
        val pl = abs(l); val pr = abs(r)
        if (20f * log10(pl.coerceAtLeast(1e-6f)) > peakDbL) peakDbL = 20f * log10(pl.coerceAtLeast(1e-6f))
        if (20f * log10(pr.coerceAtLeast(1e-6f)) > peakDbR) peakDbR = 20f * log10(pr.coerceAtLeast(1e-6f))
        if (count >= 1000) {
            val rms = sqrt((sumSqL + sumSqR) / (count * 2f))
            momentaryLufs = if (rms > 1e-6f) 20f * log10(rms) - 0.691f else -60f
            integratedLufs = momentaryLufs
            sumSqL = 0f; sumSqR = 0f; count = 0
        }
    }
    fun reset() { sumSqL = 0f; sumSqR = 0f; count = 0; integratedLufs = -60f; momentaryLufs = -60f; peakDbL = -60f; peakDbR = -60f }
}

// ============================================================
// ResonanceSmootherProcessor
// ============================================================
class ResonanceSmootherProcessor(private val sampleRate: Float = 48000f) {
    var enabled = true
    var frequencyHz = 3200f
    var thresholdDb = -18f
    var depthDb = -6f
    var qFactor = 4f
    var currentReductionDb = 0f

    // Stereo band-pass detector. Only the offending resonance band is
    // subtracted, so the rest of the vocal keeps its body and air.
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1L = 0f; private var x2L = 0f; private var y1L = 0f; private var y2L = 0f
    private var x1R = 0f; private var x2R = 0f; private var y1R = 0f; private var y2R = 0f
    private var amount = 0f
    private var targetAmount = 0f
    private var controlCounter = 0
    private var attackCoeff = 0.99f
    private var releaseCoeff = 0.999f

    init { recalculate() }

    private fun recalculate() {
        val f = frequencyHz.coerceIn(200f, sampleRate * 0.45f)
        val q = qFactor.coerceIn(0.5f, 20f)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / (2f * q)
        val a0 = 1f + alpha
        b0 = alpha / a0
        b1 = 0f
        b2 = -alpha / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha) / a0
        attackCoeff = exp(-1.0 / (0.002 * sampleRate)).toFloat()
        releaseCoeff = exp(-1.0 / (0.080 * sampleRate)).toFloat()
    }

    fun configure(config: com.example.audio.model.ResonanceSmootherConfig) {
        enabled = config.enabled
        frequencyHz = config.frequencyHz
        thresholdDb = config.thresholdDb
        depthDb = config.depthDb.coerceIn(-18f, 0f)
        qFactor = config.qFactor
        recalculate()
    }
    fun processSample(inL: Float, inR: Float): Pair<Float, Float> { val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1]) }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) {
            currentReductionDb = 0f
            out[0] = inL; out[1] = inR; return
        }
        val bandL = b0 * inL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L; x1L = inL; y2L = y1L; y1L = bandL
        val bandR = b0 * inR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R; x1R = inR; y2R = y1R; y1R = bandR

        // The detector only needs to update its target every 32 samples; the
        // per-sample path stays cheap for 16 simultaneous channels.
        controlCounter++
        val controlTick = controlCounter >= 32
        if (controlTick) {
            controlCounter = 0
            val bandPeak = max(abs(bandL), abs(bandR))
            val bandDb = if (bandPeak > 1e-6f) 20f * log10(bandPeak) else -100f
            val overDb = bandDb - thresholdDb
            val maxNotch = (1f - 10.0.pow(depthDb / 20.0)).toFloat().coerceIn(0f, 0.95f)
            targetAmount = if (overDb > 0f) ((overDb / 12f).coerceIn(0f, 1f) * maxNotch) else 0f
        }
        amount = if (targetAmount > amount) targetAmount + attackCoeff * (amount - targetAmount)
            else targetAmount + releaseCoeff * (amount - targetAmount)
        if (controlTick) {
            currentReductionDb = if (amount > 0.0001f) 20f * log10((1f - amount).coerceAtLeast(1e-4f)) else 0f
        }
        out[0] = inL - bandL * amount
        out[1] = inR - bandR * amount
    }
    fun reset() {
        amount = 0f; targetAmount = 0f; controlCounter = 0; currentReductionDb = 0f
        x1L = 0f; x2L = 0f; y1L = 0f; y2L = 0f
        x1R = 0f; x2R = 0f; y1R = 0f; y2R = 0f
    }
}

// ============================================================
// VocalRiderProcessor
// ============================================================
class VocalRiderProcessor(private val sampleRate: Float = 48000f) {
    var enabled = true
    var targetDb = -18f
    var maxGainDb = 6f
    var minGainDb = -6f
    var sensitivity = 0.5f
    var currentGainDb = 0f
    private var detector = 0f
    private var gainDb = 0f
    private var gainLinear = 1f
    private var targetGainLinear = 1f
    private var controlCounter = 0
    private var detectorAttack = 0.99f
    private var detectorRelease = 0.999f
    private var gainCoeff = 0.0005f
    private val noiseFloorDb = -55f

    fun configure(config: com.example.audio.model.VocalRiderConfig) {
        enabled = config.enabled
        targetDb = config.targetLufs
        maxGainDb = config.maxGainDb.coerceAtLeast(0f)
        minGainDb = config.minGainDb.coerceAtMost(0f)
        sensitivity = config.sensitivity.coerceIn(0f, 1f)
        val attackMs = 30f - 20f * sensitivity
        val releaseMs = 500f - 400f * sensitivity
        detectorAttack = exp(-1.0 / (attackMs * sampleRate / 1000.0)).toFloat()
        detectorRelease = exp(-1.0 / (releaseMs * sampleRate / 1000.0)).toFloat()
        gainCoeff = (1.0 - exp(-1.0 / (0.035 * sampleRate))).toFloat()
    }
    fun processSample(inL: Float, inR: Float): Pair<Float, Float> { val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1]) }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) {
            currentGainDb = 0f; gainDb = 0f; gainLinear = 1f; targetGainLinear = 1f
            out[0] = inL; out[1] = inR; return
        }
        val mono = (inL + inR) * 0.5f
        val power = mono * mono
        val coeff = if (power > detector) detectorAttack else detectorRelease
        detector = power + coeff * (detector - power)

        controlCounter++
        val controlTick = controlCounter >= 32
        if (controlTick) {
            controlCounter = 0
            val levelDb = if (detector > 1e-8f) 10f * log10(detector) else -100f
            val desiredGain = if (levelDb < noiseFloorDb) 0f else (targetDb - levelDb).coerceIn(minGainDb, maxGainDb)
            targetGainLinear = 10.0.pow(desiredGain / 20.0).toFloat()
            gainDb = desiredGain
        }
        gainLinear += (targetGainLinear - gainLinear) * gainCoeff
        if (controlTick) {
            currentGainDb = if (gainLinear > 1e-6f) 20f * log10(gainLinear) else -100f
        }
        out[0] = inL * gainLinear
        out[1] = inR * gainLinear
    }
    fun reset() {
        detector = 0f; gainDb = 0f; gainLinear = 1f; targetGainLinear = 1f
        controlCounter = 0; currentGainDb = 0f
    }
}

// ============================================================
// FadeMath
// ============================================================
object FadeMath {
    fun calculateFadeFactor(frameInFade: Long, totalFadeFrames: Long, curve: FadeCurve, isFadeIn: Boolean): Float {
        if (totalFadeFrames <= 0) return 1f
        val t = (frameInFade.toFloat() / totalFadeFrames).coerceIn(0f, 1f)
        return when (curve) {
            FadeCurve.LINEAR -> if (isFadeIn) t else 1f - t
            FadeCurve.EQUAL_POWER -> if (isFadeIn) sqrt(t) else sqrt(1f - t)
            FadeCurve.EXPONENTIAL -> if (isFadeIn) t * t else 1f - t * t
            FadeCurve.S_CURVE -> { val s = t * t * (3f - 2f * t); if (isFadeIn) s else 1f - s }
        }
    }
}

// ============================================================
// ClipCompositor
// ============================================================
object ClipCompositor {
    fun compositeClipsFromSources(sources: Map<String, FloatArray>, clips: List<AudioClip>, totalTrackFrames: Long): FloatArray =
        composite(clips, totalTrackFrames) { sources[it.sourcePath] }

    /**
     * The single mixing loop for clip playback. A clip whose source is missing
     * is silence, and a mono tail is mirrored so a truncated file cannot leave
     * the right channel silent.
     */
    private inline fun composite(
        clips: List<AudioClip>,
        totalTrackFrames: Long,
        sourceFor: (AudioClip) -> FloatArray?,
    ): FloatArray {
        val totalFrames = totalTrackFrames.coerceAtLeast(0L)
        val totalLength = totalFrames.toInt() * 2
        val output = FloatArray(totalLength)
        if (totalLength == 0) return output
        for (clip in clips) {
            val sourceBuffer = sourceFor(clip) ?: continue
            val clipStartFrame = clip.timelineStartFrame
            val clipLenFrames = clip.lengthFrames
            val gain = clip.gainLinear
            for (f in 0L until clipLenFrames) {
                val targetFrame = clipStartFrame + f
                if (targetFrame < 0L || targetFrame >= totalFrames) continue
                val sourceFrame = clip.sourceStartFrame + f
                val srcIdx = (sourceFrame * 2).toInt()
                var sampleL = 0f
                var sampleR = 0f
                if (srcIdx + 1 < sourceBuffer.size) {
                    sampleL = sourceBuffer[srcIdx]
                    sampleR = sourceBuffer[srcIdx + 1]
                } else if (srcIdx < sourceBuffer.size) {
                    sampleL = sourceBuffer[srcIdx]
                    sampleR = sampleL
                }
                var fadeMult = 1f
                if (clip.fadeInFrames > 0L && f < clip.fadeInFrames)
                    fadeMult *= FadeMath.calculateFadeFactor(f, clip.fadeInFrames, clip.fadeInCurve, isFadeIn = true)
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

// ============================================================
// TruePeakLimiter
// ============================================================
class TruePeakLimiter(sampleRate: Float = 48000f, ceilingDb: Float = -1.0f) {
    private val ceilingLinear = 10.0.pow(ceilingDb / 20.0).toFloat()
    private var envelope = 0f
    private val releaseCoeff = kotlin.math.exp(-1.0 / (0.05 * sampleRate)).toFloat()
    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val peak = maxOf(abs(inL), abs(inR)) * 1.1f
        envelope = maxOf(peak, envelope * releaseCoeff)
        val gain = if (envelope > ceilingLinear) ceilingLinear / envelope else 1f
        return Pair(softClip(inL * gain, ceilingLinear), softClip(inR * gain, ceilingLinear))
    }
    private fun softClip(x: Float, ceiling: Float): Float {
        val ax = abs(x)
        return if (ax <= ceiling) x else (if (x >= 0) 1f else -1f) * (ceiling + (ax - ceiling) / (1f + (ax - ceiling) * 2f))
    }
    fun reset() { envelope = 0f }
}

// ============================================================
// DeEsser
// ============================================================
class DeEsser(sampleRate: Float = 48000f, private val centerFreq: Float = 7000f) {
    var enabled = true
    var thresholdDb = -20f
    var ratio = 4f
    private var reduction = 0f
    private val attackCoeff = exp(-1.0 / (0.005 * sampleRate)).toFloat()
    private val releaseCoeff = exp(-1.0 / (0.05 * sampleRate)).toFloat()
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f
    init {
        val w0 = 2.0 * Math.PI * centerFreq / sampleRate
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / 2f
        val a0 = 1f + alpha
        b0 = alpha / a0; b1 = 0f; b2 = -alpha / a0
        a1 = -2f * cosW0 / a0; a2 = (1f - alpha) / a0
    }
    fun configure(enabled: Boolean, thresholdDb: Float, ratio: Float) {
        this.enabled = enabled; this.thresholdDb = thresholdDb; this.ratio = ratio.coerceAtLeast(1f)
    }
    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        if (!enabled) return Pair(inL, inR)
        val mid = (inL + inR) * 0.5f
        val bandOut = b0 * mid + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = mid; y2 = y1; y1 = bandOut
        val bandLevel = abs(bandOut)
        val bandDb = if (bandLevel > 1e-5f) 20f * log10(bandLevel) else -100f
        val overDb = bandDb - thresholdDb
        val targetReduction = if (overDb > 0f) overDb * (1f - 1f / ratio) else 0f
        reduction = if (targetReduction > reduction) targetReduction + attackCoeff * (reduction - targetReduction)
            else targetReduction + releaseCoeff * (reduction - targetReduction)
        val gainLinear = 10.0.pow(-reduction / 20.0).toFloat()
        val sibilanceGain = gainLinear
        return Pair(inL * (1f - (1f - sibilanceGain) * 0.5f), inR * (1f - (1f - sibilanceGain) * 0.5f))
    }
    fun reset() { reduction = 0f; x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

// ============================================================
// SpectrumAnalyzer
// ============================================================
class SpectrumAnalyzer(private val fftSize: Int = 2048) {
    private val window = FloatArray(fftSize) { i ->
        (0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }
    fun analyze(samples: FloatArray): FloatArray {
        val n = fftSize.coerceAtMost(samples.size / 2)
        val real = FloatArray(fftSize); val imag = FloatArray(fftSize)
        for (i in 0 until n) {
            val idx = i * 2
            if (idx < samples.size) real[i] = samples[idx] * window[i]
        }
        fft(real, imag)
        val half = fftSize / 2
        val magnitude = FloatArray(half)
        for (i in 0 until half) magnitude[i] = sqrt((real[i] * real[i] + imag[i] * imag[i]).toDouble()).toFloat()
        return magnitude
    }
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wReCur = 1f; var wImCur = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]; val uIm = imag[i + k]
                    val tRe = wReCur * real[i + k + len / 2] - wImCur * imag[i + k + len / 2]
                    val tIm = wReCur * imag[i + k + len / 2] + wImCur * real[i + k + len / 2]
                    real[i + k] = uRe + tRe; imag[i + k] = uIm + tIm
                    real[i + k + len / 2] = uRe - tRe; imag[i + k + len / 2] = uIm - tIm
                    val nwRe = wReCur * wRe - wImCur * wIm
                    val nwIm = wReCur * wIm + wImCur * wRe
                    wReCur = nwRe; wImCur = nwIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}

// ============================================================
// PeakLimiter
// ============================================================
class PeakLimiter(private val sampleRate: Float) {
    var enabled = true
    private var envelope = 0f
    private var ceilingDb = -0.1f
    private var ceilingLinear = 10.0.pow(ceilingDb / 20.0).toFloat()
    private var releaseCoeff = kotlin.math.exp(-1.0 / (0.01 * sampleRate)).toFloat()

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val peak = max(abs(inL), abs(inR))
        envelope = max(peak, envelope * releaseCoeff)
        val gain = if (envelope > ceilingLinear) ceilingLinear / envelope else 1f
        out[0] = softSaturate(inL * gain, ceilingLinear)
        out[1] = softSaturate(inR * gain, ceilingLinear)
    }
    private fun softSaturate(x: Float, ceiling: Float): Float {
        val ax = abs(x)
        return if (ax <= ceiling) x else (if (x >= 0) 1f else -1f) * (ceiling + (ax - ceiling) / (1f + (ax - ceiling) * 2f))
    }
    fun configure(config: com.example.audio.model.MasterLimiterConfig) {
        enabled = config.enabled
        ceilingDb = config.ceilingDb
        ceilingLinear = 10.0.pow(ceilingDb / 20.0).toFloat()
        releaseCoeff = kotlin.math.exp(-1.0 / (config.releaseMs * sampleRate / 1000.0)).toFloat()
    }
    fun reset() { envelope = 0f }
}

// ============================================================
// SchroederReverb
// ============================================================
class SchroederReverb(private val sampleRate: Float) {
    var enabled = true
    var returnLevel = 0.3f
    var roomSize = 0.5f
    var damping = 0.5f
    var stereoSpread = 0.85f
    var decayTimeSec = 2.2f
    private val combFiltersL = arrayOfNulls<CombFilter>(4)
    private val combFiltersR = arrayOfNulls<CombFilter>(4)
    private val allpassFiltersL = arrayOfNulls<AllpassFilter>(2)
    private val allpassFiltersR = arrayOfNulls<AllpassFilter>(2)
    // ReverbConfig documents 0–200 ms of pre-delay.
    private val preDelayBuffer = FloatArray((sampleRate * 0.21f).toInt() + 1)
    private var preDelayWriteIdx = 0
    private var preDelayReadFrames = (sampleRate * 0.02f).toInt()
    private val maxPreDelayFrames = preDelayBuffer.size
    private val lowCutFilter = BiquadFilter(sampleRate)
    private val highCutFilter = BiquadFilter(sampleRate)

    init {
        for (i in 0 until 4) {
            combFiltersL[i] = CombFilter(sampleRate, 0.84f - i * 0.02f)
            combFiltersR[i] = CombFilter(sampleRate, 0.84f - i * 0.02f)
        }
        for (i in 0 until 2) {
            allpassFiltersL[i] = AllpassFilter(sampleRate, 0.7f)
            allpassFiltersR[i] = AllpassFilter(sampleRate, 0.7f)
        }
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }
    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled || returnLevel <= 0.001f) { out[0] = 0f; out[1] = 0f; return }
        val rawMono = (inL + inR) * 0.5f * 0.05f
        preDelayBuffer[preDelayWriteIdx] = rawMono
        val readIdx = (preDelayWriteIdx - preDelayReadFrames + maxPreDelayFrames) % maxPreDelayFrames
        val delayedMono = preDelayBuffer[readIdx]
        preDelayWriteIdx = (preDelayWriteIdx + 1) % maxPreDelayFrames
        val tmpMono = FloatArray(2); val tmpMono2 = FloatArray(2)
        lowCutFilter.processSampleInto(delayedMono, 0f, tmpMono)
        highCutFilter.processSampleInto(tmpMono[0], 0f, tmpMono2)
        val filteredMono2 = tmpMono2[0]
        val decayScale = (decayTimeSec / 2.2f).coerceIn(0.5f, 2f)
        val feedback = ((roomSize * 0.28f + 0.7f) * (0.85f + 0.15f * decayScale)).coerceIn(0.5f, 0.98f)
        var outL = 0f; var outR = 0f
        for (i in 0 until 4) {
            outL += combFiltersL[i]!!.process(filteredMono2, feedback, damping)
            outR += combFiltersR[i]!!.process(filteredMono2, feedback, damping)
        }
        for (ap in allpassFiltersL) outL = ap!!.process(outL)
        for (ap in allpassFiltersR) outR = ap!!.process(outR)
        val mid = (outL + outR) * 0.5f
        val side = (outL - outR) * 0.5f * stereoSpread
        out[0] = (mid + side) * returnLevel
        out[1] = (mid - side) * returnLevel
    }
    fun configure(config: com.example.audio.model.ReverbConfig) {
        enabled = config.enabled
        returnLevel = config.returnLevel
        roomSize = config.roomSize
        damping = config.damping
        stereoSpread = config.stereoWidth
        decayTimeSec = config.decayTimeSec
        // Pre-delay, low cut and high cut were exposed knobs that never reached
        // the DSP: pre-delay was fixed at 20 ms and both filters stayed at
        // unity. Apply them here, clamped to the buffers/ranges they own.
        preDelayReadFrames = (config.preDelayMs.coerceIn(0f, 200f) * sampleRate / 1000f)
            .toInt().coerceIn(0, maxPreDelayFrames - 1)
        lowCutFilter.configure(EqBand(FilterType.HIGH_PASS, config.lowCutHz.coerceIn(20f, 1000f), 0f, 0.707f))
        highCutFilter.configure(EqBand(FilterType.LOW_PASS, config.highCutHz.coerceIn(1000f, 20000f), 0f, 0.707f))
    }
    fun reset() {
        preDelayBuffer.fill(0f)
        preDelayWriteIdx = 0
        lowCutFilter.reset()
        highCutFilter.reset()
        for (filter in combFiltersL) filter?.reset()
        for (filter in combFiltersR) filter?.reset()
        for (filter in allpassFiltersL) filter?.reset()
        for (filter in allpassFiltersR) filter?.reset()
    }

    private class CombFilter(sampleRate: Float, private val feedback: Float) {
        private val delaySize = (sampleRate * 0.03f).toInt()
        private val buffer = FloatArray(delaySize)
        private var idx = 0
        private var filterState = 0f
        fun process(input: Float, fb: Float, damp: Float): Float {
            val output = buffer[idx]
            filterState = output * (1f - damp) + filterState * damp
            buffer[idx] = input + filterState * fb
            idx = (idx + 1) % delaySize
            return output
        }
        fun reset() {
            buffer.fill(0f)
            idx = 0
            filterState = 0f
        }
    }

    private class AllpassFilter(sampleRate: Float, private val feedback: Float) {
        private val delaySize = (sampleRate * 0.005f).toInt()
        private val buffer = FloatArray(delaySize)
        private var idx = 0
        fun process(input: Float): Float {
            val delayed = buffer[idx]
            val output = -input + delayed
            buffer[idx] = input + delayed * feedback
            idx = (idx + 1) % delaySize
            return output
        }
        fun reset() {
            buffer.fill(0f)
            idx = 0
        }
    }
}

// ============================================================
// AutoPitchProcessor — monophonic pitch correction to nearest semitone
// ============================================================
class AutoPitchProcessor(private val sampleRate: Float = 48000f) {
    var enabled = true
    var correctionStrength = 1.0f

    fun configure(enabled: Boolean, correctionStrength: Float = 1.0f) {
        this.enabled = enabled
        this.correctionStrength = correctionStrength.coerceIn(0f, 1f)
    }

    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        out[0] = inL; out[1] = inR
    }

    fun processBuffer(input: FloatArray): FloatArray {
        if (!enabled || input.size < 4) return input.copyOf()
        val frames = input.size / 2
        if (frames < 256) return input.copyOf()

        val mono = FloatArray(frames) { i -> (input[i * 2] + input[i * 2 + 1]) * 0.5f }

        val windowSize = minOf(2048, frames)
        val f0 = detectPitch(mono, windowSize)
        if (f0 <= 40f || f0 > sampleRate * 0.25f) return input.copyOf()

        val midiNote = 69f + 12f * (kotlin.math.ln(f0 / 440f) / kotlin.math.ln(2f))
        val targetMidi = kotlin.math.round(midiNote)
        val targetFreq = 440f * 2f.pow((targetMidi - 69f) / 12f)
        val ratio = (targetFreq / f0).coerceIn(0.5f, 2.0f)
        if (kotlin.math.abs(ratio - 1f) < 1e-3f) return input.copyOf()

        val effectiveRatio = 1f + (ratio - 1f) * correctionStrength
        if (kotlin.math.abs(effectiveRatio - 1f) < 1e-3f) return input.copyOf()

        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val srcPos = i * effectiveRatio
            val i0 = srcPos.toInt().coerceIn(0, frames - 2)
            val frac = srcPos - i0
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            out[i * 2] = input[i0 * 2] * (1f - frac) + input[i1 * 2] * frac
            out[i * 2 + 1] = input[i0 * 2 + 1] * (1f - frac) + input[i1 * 2 + 1] * frac
        }
        return out
    }

    private fun detectPitch(mono: FloatArray, windowSize: Int): Float {
        val minLag = (sampleRate / 1000f).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / 40f).toInt().coerceAtMost(windowSize / 2)
        var bestLag = -1
        var bestCorr = 0f
        var rms = 0f
        for (i in 0 until windowSize) rms += mono[i] * mono[i]
        rms = kotlin.math.sqrt(rms / windowSize)
        if (rms < 1e-4f) return 0f
        for (lag in minLag..maxLag) {
            var corr = 0f
            for (i in 0 until windowSize - lag) corr += mono[i] * mono[i + lag]
            val norm = corr / (windowSize - lag)
            if (norm > bestCorr) { bestCorr = norm; bestLag = lag }
        }
        if (bestLag <= 0 || bestCorr < rms * rms * 0.3f) return 0f
        return sampleRate / bestLag
    }

    fun reset() {}
}

// ============================================================
// NoiseReductionProcessor — spectral noise gate / reduction
// ============================================================
class NoiseReductionProcessor(private val sampleRate: Float = 48000f) {
    var enabled = true
    var thresholdDb = -40f
    var reductionDb = -12f
    private var gain = 1f
    private val attackCoeff = kotlin.math.exp(-1.0 / (5.0 * sampleRate / 1000.0)).toFloat()
    private val releaseCoeff = kotlin.math.exp(-1.0 / (50.0 * sampleRate / 1000.0)).toFloat()

    fun configure(enabled: Boolean, thresholdDb: Float = -40f, reductionDb: Float = -12f) {
        this.enabled = enabled
        this.thresholdDb = thresholdDb
        this.reductionDb = reductionDb
    }

    fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val thresholdLinear = 10.0.pow(thresholdDb / 20.0).toFloat()
        val reductionLinear = 10.0.pow(reductionDb / 20.0).toFloat()
        val level = maxOf(kotlin.math.abs(inL), kotlin.math.abs(inR))
        val targetGain = if (level < thresholdLinear) reductionLinear else 1f
        gain = if (targetGain < gain) targetGain + attackCoeff * (gain - targetGain)
            else targetGain + releaseCoeff * (gain - targetGain)
        out[0] = inL * gain
        out[1] = inR * gain
    }

    fun processSample(inL: Float, inR: Float): Pair<Float, Float> {
        val out = FloatArray(2); processSampleInto(inL, inR, out); return Pair(out[0], out[1])
    }

    fun reset() { gain = 1f }
}

// ============================================================
// AutomationInterpolator
// ============================================================
object AutomationInterpolator {
    fun valueAt(points: List<com.example.audio.model.AutomationPoint>, frame: Long, default: Float = 0f): Float {
        if (points.isEmpty()) return default
        val sorted = points.sortedBy { it.frame }
        if (frame <= sorted.first().frame) return sorted.first().value
        if (frame >= sorted.last().frame) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]; val b = sorted[i + 1]
            if (frame in a.frame..b.frame) {
                val span = (b.frame - a.frame).coerceAtLeast(1L)
                val t = (frame - a.frame).toFloat() / span.toFloat()
                return a.value + (b.value - a.value) * t
            }
        }
        return sorted.last().value
    }
}

