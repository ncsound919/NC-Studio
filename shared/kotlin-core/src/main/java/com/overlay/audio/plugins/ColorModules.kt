package com.overlay.audio.plugins

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Saturation and tape modules.
 *
 * Tape is modeled as a real machine does it: record head saturation with bias
 * affecting how hard the signal is pushed and how much HF survives, a playback
 * head bump whose frequency follows the transport speed (it halves when speed
 * halves), playback HF loss, and wow/flutter as pitch modulation of a short
 * delay line. The nonlinear stages run oversampled so the generated harmonics
 * do not fold back into the audible band.
 */
class SaturationModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.SATURATION
    override var enabled = true
    private var type = 0
    private var drive = 1f
    private var warmth = 0.4f
    private var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    private var oversampler: Oversampler? = null
    private var lpL = 0f
    private var lpR = 0f
    private val scratch = FloatArray(2)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        type = slot.value("type").toInt().coerceIn(0, 3)
        drive = Db.toLinear(slot.value("driveDb"))
        warmth = slot.value("warmth")
        mixSmoother.set(slot.value("mix"))
        val factor = slot.value("oversample").toInt().coerceIn(1, 4)
        if (factor <= 1) oversampler = null
        else if (oversampler == null || oversampler?.factor != factor) oversampler = Oversampler(factor, sampleRate)
    }

    private fun shapeFor(x: Float, channel: Int): Float = when (type) {
        0 -> { // Tape: symmetric compression plus a gentle HF smoothing
            val driven = tanh(x * (1f + warmth * 0.4f))
            val smoothed = if (channel == 0) {
                lpL += 0.30f * (driven - lpL); lpL
            } else {
                lpR += 0.30f * (driven - lpR); lpR
            }
            driven * (1f - warmth * 0.2f) + smoothed * (warmth * 0.2f)
        }
        1 -> { // Tube: asymmetric, so it alone produces a 2nd harmonic
            // (measured: the 2nd sits ~13 dB below the 3rd at warmth 1.0, so
            // this curve is an odd-order colour with an even-order accent -
            // see FxDspMeasurementTest rather than assuming even dominance.)
            val bias = 1f + warmth * 0.5f
            val driven = x * (1f + warmth * 0.3f)
            if (driven >= 0f) tanh(driven * bias) else tanh(driven / bias)
        }
        2 -> { // Transformer: iron weight with low-mid bloom
            val driven = x / (1f + abs(x) * (0.8f - warmth * 0.4f))
            val weighted = if (channel == 0) {
                lpL += 0.055f * (driven - lpL); lpL
            } else {
                lpR += 0.055f * (driven - lpR); lpR
            }
            driven + weighted * warmth * 0.5f
        }
        else -> { // Console: tight op-amp clip, minimal low-end smear
            val driven = tanh(x * 1.35f)
            driven * (1f - warmth * 0.1f) + driven * driven * driven * warmth * 0.1f
        }
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val os = oversampler
        var l: Float
        var r: Float
        if (os == null) {
            l = shapeFor(inL * drive, 0) / trimForDrive()
            r = shapeFor(inR * drive, 1) / trimForDrive()
        } else {
            os.upsample(inL * drive, inR * drive)
            for (i in 0 until os.factor) {
                os.bufferL[i] = shapeFor(os.bufferL[i], 0)
                os.bufferR[i] = shapeFor(os.bufferR[i], 1)
            }
            os.downsample(scratch)
            val trim = trimForDrive()
            l = scratch[0] / trim
            r = scratch[1] / trim
        }
        val mix = mixSmoother.next()
        out[0] = inL + (l - inL) * mix
        out[1] = inR + (r - inR) * mix
        outputMeter.update(out[0], out[1])
    }

    private fun trimForDrive(): Float = 1f + (drive - 1f) * 0.18f

    override fun reset() {
        lpL = 0f; lpR = 0f; oversampler?.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Tape machine: record level, tape speed, bias, wow/flutter and playback EQ.
 */
class TapeMachineModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.TAPE_MACHINE
    override var enabled = true
    private var drive = 1f
    private var speedIps = 15f
    private var bias = 0f
    private var wowFlutter = 0.35f
    private var noiseOn = false
    private var outGain = 1f
    private var oversampler: Oversampler? = null

    // Playback EQ: head bump and HF loss follow the transport speed.
    private val headBumpL = Biquad(sampleRate)
    private val headBumpR = Biquad(sampleRate)
    private val hfLossL = Biquad(sampleRate)
    private val hfLossR = Biquad(sampleRate)
    private val subsonicL = Biquad(sampleRate)
    private val subsonicR = Biquad(sampleRate)

    // Transport: wow (slow) and flutter (fast) modulate a short delay line.
    private val delaySize = (sampleRate * 0.02f).toInt() + 4
    private val delayL = FloatArray(delaySize)
    private val delayR = FloatArray(delaySize)
    private var writeIndex = 0
    private var wowPhase = 0f
    private var flutterPhase = 0f
    private var wowRate = 1.2f
    private var flutterRate = 7.5f
    private var noiseState = 0x12345678

    private val scratch = FloatArray(2)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        drive = Db.toLinear(slot.value("driveDb"))
        speedIps = slot.value("speedIps")
        bias = slot.value("bias")
        wowFlutter = slot.value("wowFlutter")
        noiseOn = slot.value("noise") >= 0.5f
        outGain = Db.toLinear(slot.value("outDb"))
        val factor = if (abs(bias) > 0.1f || drive > 2f) 4 else 2
        if (oversampler == null || oversampler?.factor != factor) oversampler = Oversampler(factor, sampleRate)

        // Head bump: ~60 Hz at 15 ips, one octave lower at 7.5 and higher at 30.
        val bumpHz = when {
            speedIps <= 7.5f -> 40f
            speedIps <= 15f -> 60f
            else -> 95f
        }
        val bumpDb = when {
            speedIps <= 7.5f -> 3.5f
            speedIps <= 15f -> 2.5f
            else -> 1.5f
        }
        headBumpL.setLowShelf(bumpHz, bumpDb, 0.8f)
        headBumpR.setLowShelf(bumpHz, bumpDb, 0.8f)
        // Over-bias dulls the top end; under-bias keeps more HF but adds edge.
        val hf = when {
            speedIps <= 7.5f -> 12000f
            speedIps <= 15f -> 16000f
            else -> 20000f
        } * (1f - bias.coerceAtLeast(0f) * 0.25f)
        hfLossL.setLowPass(hf.coerceAtLeast(6000f), 0.707f)
        hfLossR.setLowPass(hf.coerceAtLeast(6000f), 0.707f)
        subsonicL.setHighPass(25f, 0.707f)
        subsonicR.setHighPass(25f, 0.707f)
        wowRate = 1.2f
        flutterRate = 7.5f
    }

    private fun recordShape(x: Float): Float {
        // Bias sets where the record head sits: over-bias compresses and
        // dulls, under-bias distorts earlier.
        val biasDrive = 1f + (0.35f * (1f - bias.coerceAtLeast(0f)))
        val driven = x * biasDrive
        val shape = tanh(driven)
        // Fixed headroom loss and a small third-harmonic term.
        return shape - 0.06f * shape * shape * shape
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val os = oversampler
        var l: Float
        var r: Float
        if (os == null) {
            l = recordShape(inL * drive); r = recordShape(inR * drive)
        } else {
            os.upsample(inL * drive, inR * drive)
            for (i in 0 until os.factor) {
                os.bufferL[i] = recordShape(os.bufferL[i])
                os.bufferR[i] = recordShape(os.bufferR[i])
            }
            os.downsample(scratch)
            l = scratch[0]; r = scratch[1]
        }

        // Wow / flutter: pitch modulation via a short modulated delay.
        wowPhase += 2f * PI.toFloat() * wowRate / sampleRate
        if (wowPhase >= 2f * PI.toFloat()) wowPhase -= 2f * PI.toFloat()
        flutterPhase += 2f * PI.toFloat() * flutterRate / sampleRate
        if (flutterPhase >= 2f * PI.toFloat()) flutterPhase -= 2f * PI.toFloat()
        val baseDelayFrames = sampleRate * 0.004f
        // Peak wow/flutter lands near the 0.04% transport spec, not a broken-machine effect.
        val modFrames = wowFlutter * sampleRate * 0.00006f *
            (0.7f * sin(wowPhase) + 0.3f * sin(flutterPhase))
        val readL = (writeIndex - baseDelayFrames - modFrames + delaySize * 2) % delaySize
        val readR = (writeIndex - baseDelayFrames - modFrames * 1.02f + delaySize * 2) % delaySize
        val iL = readL.toInt(); val fL = readL - iL
        val iR = readR.toInt(); val fR = readR - iR
        val outDelayedL = delayL[iL] * (1f - fL) + delayL[(iL + 1) % delaySize] * fL
        val outDelayedR = delayR[iR] * (1f - fR) + delayR[(iR + 1) % delaySize] * fR
        delayL[writeIndex] = l
        delayR[writeIndex] = r
        writeIndex = (writeIndex + 1) % delaySize
        l = outDelayedL
        r = outDelayedR

        l = headBumpL.process(subsonicL.process(l))
        r = headBumpR.process(subsonicR.process(r))
        l = hfLossL.process(l)
        r = hfLossR.process(r)

        if (noiseOn) {
            l += nextNoise() * 0.0006f
            r += nextNoise() * 0.0006f
        }
        out[0] = l * outGain
        out[1] = r * outGain
        outputMeter.update(out[0], out[1])
    }

    /** Deterministic LCG so tape noise is reproducible in tests. */
    private fun nextNoise(): Float {
        noiseState = noiseState * 1664525 + 1013904223
        return ((noiseState ushr 8) and 0xFFFF) / 32768f - 1f
    }

    override fun reset() {
        delayL.fill(0f); delayR.fill(0f); writeIndex = 0
        wowPhase = 0f; flutterPhase = 0f
        headBumpL.reset(); headBumpR.reset(); hfLossL.reset(); hfLossR.reset()
        subsonicL.reset(); subsonicR.reset(); oversampler?.reset()
        inputMeter.reset(); outputMeter.reset()
    }
}
