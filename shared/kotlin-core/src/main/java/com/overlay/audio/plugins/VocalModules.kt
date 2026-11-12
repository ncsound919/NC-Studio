package com.overlay.audio.plugins

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * Vocal-chain modules that used to live only in the legacy fixed channel strip.
 *
 * Keeping them in the ordered rack lets the engineer place the rider, denoise
 * and resonance control before compression - where they belong - instead of
 * being forced into one hard-coded signal flow. All state is owned by the
 * module, so the audio path stays allocation-free.
 */

/**
 * Automatic level rider: a slow RMS detector writes a bounded gain offset so
 * the compressor stages receive a consistent vocal instead of one word at
 * -12 dBFS and the next at -30 dBFS. Bounded by [minGainDb]/[maxGainDb] so a
 * silent gap cannot make it amplify noise.
 */
class VocalRiderModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.VOCAL_RIDER
    override var enabled = true

    private var targetDb = -18f
    private var maxGainDb = 5f
    private var minGainDb = -5f
    private var detectorAttack = DynMath.coeffMs(20f, sampleRate)
    private var detectorRelease = DynMath.coeffMs(280f, sampleRate)
    private var gainStep = 0.001f

    private var detector = 0f
    private var gain = 1f
    private var targetGain = 1f
    private var currentGainDb = 0f
    private var controlCounter = 0

    override val gainReductionDb: Float
        get() = (-currentGainDb).coerceAtLeast(0f)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        targetDb = slot.value("targetLufs")
        maxGainDb = slot.value("maxGainDb").coerceAtLeast(0f)
        minGainDb = slot.value("minGainDb").coerceAtMost(0f)
        val sensitivity = slot.value("sensitivity").coerceIn(0f, 1f)
        val attackMs = 30f - 20f * sensitivity
        val releaseMs = 500f - 400f * sensitivity
        detectorAttack = DynMath.coeffMs(attackMs, sampleRate)
        detectorRelease = DynMath.coeffMs(releaseMs, sampleRate)
        gainStep = DynMath.smoothingStep(DynMath.coeffMs(35f, sampleRate))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) {
            currentGainDb = 0f
            gain = 1f
            targetGain = 1f
            out[0] = inL
            out[1] = inR
            return
        }
        val mono = (inL + inR) * 0.5f
        val power = mono * mono
        val coeff = if (power > detector) detectorAttack else detectorRelease
        detector = power + coeff * (detector - power)

        controlCounter++
        if (controlCounter >= 32) {
            controlCounter = 0
            val levelDb = if (detector > 1e-8f) 10f * log10(detector) else -100f
            val desired = if (levelDb < -55f) {
                0f
            } else {
                (targetDb - levelDb).coerceIn(minGainDb, maxGainDb)
            }
            targetGain = Db.toLinear(desired)
            currentGainDb = desired
        }
        gain += (targetGain - gain) * gainStep
        out[0] = inL * gain
        out[1] = inR * gain
    }

    override fun reset() {
        detector = 0f
        gain = 1f
        targetGain = 1f
        currentGainDb = 0f
        controlCounter = 0
    }
}

/**
 * Dynamic resonance smoother. A stereo band-pass detector finds the offending
 * formant (2-5 kHz is the usual harsh zone on a phone vocal) and subtracts only
 * that band, gated by [thresholdDb]. Unlike a static notch it releases when the
 * singer moves away from the note, so the vowel does not become hollow.
 */
class ResonanceSmootherModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.RESONANCE_SMOOTHER
    override var enabled = true

    private var frequencyHz = 3200f
    private var thresholdDb = -20f
    private var maxNotch = 0.5f
    private var qFactor = 4f

    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1L = 0f
    private var x2L = 0f
    private var y1L = 0f
    private var y2L = 0f
    private var x1R = 0f
    private var x2R = 0f
    private var y1R = 0f
    private var y2R = 0f

    private var amount = 0f
    private var targetAmount = 0f
    private var currentReductionDb = 0f
    private var controlCounter = 0
    private var attackCoeff = 0.99f
    private var releaseCoeff = 0.999f

    override val gainReductionDb: Float get() = -currentReductionDb

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        frequencyHz = slot.value("frequencyHz")
        thresholdDb = slot.value("thresholdDb")
        maxNotch = (1f - Db.toLinear(-slot.value("depthDb"))).coerceIn(0f, 0.95f)
        qFactor = slot.value("qFactor")
        recalculate()
    }

    private fun recalculate() {
        val f = frequencyHz.coerceIn(200f, sampleRate * 0.45f)
        val q = qFactor.coerceIn(0.5f, 20f)
        val w0 = 2.0 * PI * f / sampleRate
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

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) {
            currentReductionDb = 0f
            out[0] = inL
            out[1] = inR
            return
        }
        val bandL = b0 * inL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = inL
        y2L = y1L
        y1L = bandL

        val bandR = b0 * inR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = inR
        y2R = y1R
        y1R = bandR

        controlCounter++
        val controlTick = controlCounter >= 32
        if (controlTick) {
            controlCounter = 0
            val bandPeak = maxOf(abs(bandL), abs(bandR))
            val bandDb = if (bandPeak > 1e-6f) 20f * log10(bandPeak) else -100f
            val overDb = bandDb - thresholdDb
            targetAmount = if (overDb > 0f) {
                (overDb / 12f).coerceIn(0f, 1f) * maxNotch
            } else {
                0f
            }
        }
        amount = if (targetAmount > amount) {
            targetAmount + attackCoeff * (amount - targetAmount)
        } else {
            targetAmount + releaseCoeff * (amount - targetAmount)
        }
        if (controlTick) {
            currentReductionDb = if (amount > 0.0001f) {
                20f * log10((1f - amount).coerceAtLeast(1e-4f))
            } else {
                0f
            }
        }
        out[0] = inL - bandL * amount
        out[1] = inR - bandR * amount
    }

    override fun reset() {
        amount = 0f
        targetAmount = 0f
        controlCounter = 0
        currentReductionDb = 0f
        x1L = 0f
        x2L = 0f
        y1L = 0f
        y2L = 0f
        x1R = 0f
        x2R = 0f
        y1R = 0f
        y2R = 0f
    }
}
