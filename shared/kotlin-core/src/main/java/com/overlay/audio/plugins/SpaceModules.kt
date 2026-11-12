package com.overlay.audio.plugins

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reverb modules.
 *
 * A feedback delay network rather than a phone-app comb/allpass pair: four
 * delay lines mixed through an orthonormal Hadamard matrix with per-line
 * damping, input diffusion, pre-delay and output filtering. That is the
 * structure of classic digital reverbs and it is what makes a long tail stay
 * dense instead of ringing.
 *
 * - Plate: short build-up, bright, with the highs decaying before the lows.
 * - Hall: longer pre-delay, slower build-up, darker tail.
 * - Room: short, early-energy dominant, used on drums without washing them out.
 */
class AlgorithmicReverbModule(
    private val sampleRate: Float,
    private val kindOverride: PluginKind
) : RackModule {
    /** A biquad that starts transparent and is configured as a high shelf. */
    private class BiquadBypass(sampleRate: Float) {
        private val filter = Biquad(sampleRate)
        init { filter.setBypass() }
        fun setShelf(freq: Float, gainDb: Float) { filter.setHighShelf(freq, gainDb) }
        fun process(x: Float): Float = filter.process(x)
        fun reset() { filter.reset() }
    }

    override val kind: PluginKind = kindOverride
    override var enabled = true

    private val baseDelaysMs = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
    private val lineLen = IntArray(4) { (sampleRate * baseDelaysMs[it] / 1000f).toInt().coerceAtLeast(8) }
    private val lineBuf = Array(4) { FloatArray(lineLen[it] + 1) }
    private var lineWrite = 0L
    private val dampingFilters = Array(4) { BiquadBypass(sampleRate) }
    private val lineFeedback = FloatArray(4)
    private val outs = FloatArray(4)
    private val mixed = FloatArray(4)
    /** First-order allpass state per line, for the modulated fractional read. */
    private val interpInput = FloatArray(4)
    private val interpOutput = FloatArray(4)

    /**
     * The wet output is a two-line difference, so its taps add up with
     * 1/sqrt(2): the two lines are uncorrelated, and an extra half would make
     * the return sit several dB under the dry signal at 100% mix.
     */
    private val outputGain = (1.0 / sqrt(2.0)).toFloat()

    private val apLen = intArrayOf(
        (sampleRate * 0.0047f).toInt(), (sampleRate * 0.0037f).toInt(),
        (sampleRate * 0.0021f).toInt(), (sampleRate * 0.0013f).toInt()
    )
    private val apBuf = Array(4) { FloatArray(apLen[it] + 1) }
    private val apWrite = IntArray(4)
    private val apGain = 0.62f

    private val preDelaySize = (sampleRate * 0.15f).toInt() + 4
    private val preDelayBuf = FloatArray(preDelaySize)
    private var preDelayWrite = 0
    private var preDelayFrames = 0

    private val lowCutL = Biquad(sampleRate); private val lowCutR = Biquad(sampleRate)
    private val highCutL = Biquad(sampleRate); private val highCutR = Biquad(sampleRate)
    private val tiltL = Biquad(sampleRate); private val tiltR = Biquad(sampleRate)

    private val mixSmoother = SmoothValue(0.25f, sampleRate, 20f)
    private var width = 0.9f
    private var decaySec = 1.5f
    private var modulation = 0f
    private var modRate = 0.6f
    private var modPhase = 0f

    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak
    // A wet/dry insert has no latency: the dry signal still leaves at sample 0.
    // Reporting the pre-delay made the host pull the whole track earlier.
    override val latencySamples: Int get() = 0

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        decaySec = slot.value("decaySec")
        preDelayFrames = ((slot.value("preDelayMs") / 1000f) * sampleRate).toInt().coerceIn(0, preDelaySize - 4)
        val hp = slot.value("hpHz")
        val lp = slot.value("lpHz")
        lowCutL.setHighPass(hp, 0.707f); lowCutR.setHighPass(hp, 0.707f)
        highCutL.setLowPass(lp, 0.707f); highCutR.setLowPass(lp, 0.707f)
        width = slot.value("width")
        mixSmoother.set(slot.value("mix"))

        val mode = when (kindOverride) {
            PluginKind.PLATE_REVERB -> 0
            PluginKind.ROOM_REVERB -> 2
            else -> 1
        }
        // Damping is a high-shelf inside the loop, so the low end keeps the
        // configured decay while the top dies first - the behaviour of a real
        // plate / hall. The shelf is applied on every pass round the network
        // (about 28 a second), so its loss is derived from the RT60 the user
        // dialled in - the top decays in `hfDecayRatio * decaySec` seconds -
        // instead of being a fixed number of dB. A fixed -6.5 dB would end the
        // top in a third of a second and take the whole tail down with it, no
        // matter what the decay dial says.
        val (dampFreq, hfDecayRatio) = when (mode) {
            0 -> 7500f to 0.75f    // plate: bright, top holds most of the tail
            2 -> 6000f to 0.7f     // room
            else -> 5000f to 0.7f  // hall: darker, top goes in 70% of the RT60
        }
        val passesPerSecond = sampleRate / lineLen.average()
        val dialled = decaySec.coerceAtLeast(0.05f)
        val extraLossPerSecondDb = 60f / (dialled * hfDecayRatio) - 60f / dialled
        val dampDb = -(extraLossPerSecondDb / passesPerSecond.toFloat()).coerceIn(0f, 4f)
        dampingFilters.forEach { it.setShelf(dampFreq, dampDb) }
        // Modulation is a fraction of a sample up to a couple of samples: enough
        // to break up modal ringing without smearing the tail or losing energy.
        modulation = if (mode == 1) 1f else 0.35f
        modRate = if (mode == 1) 0.45f else 0.9f
        // Output tilt: an EMT plate is dense and bright, a hall is darker, so
        // the plate leans up at the top and the hall leans down.
        val tilt = when (mode) {
            0 -> 1.5f
            2 -> 0f
            else -> -1.5f
        }
        tiltL.setHighShelf(6000f, tilt); tiltR.setHighShelf(6000f, tilt)
        recomputeFeedback()
    }

    /**
     * A delay line feeding back on itself decays 60 dB in
     * `t60 = -3 * len / log10(g)`, so `g = 10^(-3 * len / t60)`.
     */
    private fun recomputeFeedback() {
        for (i in 0 until 4) {
            val lenSec = lineLen[i] / sampleRate
            lineFeedback[i] = 10.0.pow(-3.0 * lenSec / decaySec.coerceAtLeast(0.05f))
                .toFloat().coerceIn(0.02f, 0.9995f)
        }
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val mono = (inL + inR) * 0.5f

        preDelayBuf[preDelayWrite] = mono
        val preRead = (preDelayWrite - preDelayFrames + preDelaySize) % preDelaySize
        var diffused = preDelayBuf[preRead]
        // Early reflections: a few discrete taps give the reverb a sense of
        // space and attack before the diffuse tail builds up. The taps are
        // offset from the post-pre-delay read point, so the whole wet path
        // (reflections and tail alike) starts after the pre-delay; tapping the
        // raw write head instead let reflections land inside the pre-delay
        // gap — with a 40 ms pre-delay, energy at 7 ms, where the gap must
        // stay empty.
        val erOrigin = preDelayWrite - preDelayFrames
        val er1 = preDelayBuf[(erOrigin - (sampleRate * 0.007f).toInt().coerceAtLeast(1) + 2 * preDelaySize) % preDelaySize] * 0.32f
        val er2 = preDelayBuf[(erOrigin - (sampleRate * 0.013f).toInt().coerceAtLeast(1) + 2 * preDelaySize) % preDelaySize] * 0.24f
        val er3 = preDelayBuf[(erOrigin - (sampleRate * 0.021f).toInt().coerceAtLeast(1) + 2 * preDelaySize) % preDelaySize] * 0.16f
        diffused += er1 + er2 + er3
        preDelayWrite = (preDelayWrite + 1) % preDelaySize

        for (i in 0 until 4) {
            val n = apLen[i] + 1
            val buf = apBuf[i]
            val idx = apWrite[i]
            val stored = buf[idx]
            val v = diffused - apGain * stored
            buf[idx] = v
            diffused = stored + apGain * v
            apWrite[i] = (idx + 1) % n
        }

        modPhase += 2f * PI.toFloat() * modRate / sampleRate
        if (modPhase >= 2f * PI.toFloat()) modPhase -= 2f * PI.toFloat()
        val modSamples = modulation * 1.6f * sin(modPhase)

        for (i in 0 until 4) {
            val len = lineLen[i]
            val n = len + 1
            val direction = if (i % 2 == 0) 1f else -1f
            var readPos = (lineWrite - len).toFloat() + modSamples * direction
            readPos = ((readPos % n) + n) % n
            val i0 = readPos.toInt()
            val frac = readPos - i0
            val sample = lineBuf[i][i0]
            // A first-order allpass slides the reading point between samples
            // without the energy a linear fade between two taps costs on every
            // pass. That loss was shortening the tail by about a quarter of the
            // dialled RT60 as soon as the modulation was switched on.
            val delayed: Float
            if (frac <= 1e-6f) {
                interpInput[i] = sample
                interpOutput[i] = sample
                delayed = sample
            } else {
                val eta = (1f - frac) / (1f + frac)
                val interpolated = eta * (sample - interpOutput[i]) + interpInput[i]
                interpInput[i] = sample
                interpOutput[i] = interpolated
                delayed = interpolated
            }
            outs[i] = dampingFilters[i].process(delayed)
        }

        // Orthonormal 4x4 Hadamard mix: energy preserving, so the tail decays.
        val h0 = (outs[0] + outs[1] + outs[2] + outs[3]) * 0.5f
        val h1 = (outs[0] - outs[1] + outs[2] - outs[3]) * 0.5f
        val h2 = (outs[0] + outs[1] - outs[2] - outs[3]) * 0.5f
        val h3 = (outs[0] - outs[1] - outs[2] + outs[3]) * 0.5f
        mixed[0] = h0; mixed[1] = h1; mixed[2] = h2; mixed[3] = h3

        for (i in 0 until 4) {
            val n = lineLen[i] + 1
            lineBuf[i][(lineWrite % n).toInt()] = diffused * 0.5f + mixed[i] * lineFeedback[i].toFloat()
        }
        lineWrite++
        if (lineWrite > 1L shl 40) lineWrite = 0L

        var wetL = outs[0] - outs[2]
        var wetR = outs[1] - outs[3]
        wetL = highCutL.process(lowCutL.process(tiltL.process(wetL))) * outputGain
        wetR = highCutR.process(lowCutR.process(tiltR.process(wetR))) * outputGain
        val mid = (wetL + wetR) * 0.5f
        val side = (wetL - wetR) * 0.5f * width
        wetL = mid + side
        wetR = mid - side

        val mix = mixSmoother.next()
        out[0] = inL + (wetL - inL) * mix
        out[1] = inR + (wetR - inR) * mix
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        lineBuf.forEach { it.fill(0f) }
        apBuf.forEach { it.fill(0f) }
        apWrite.fill(0)
        preDelayBuf.fill(0f)
        dampingFilters.forEach { it.reset() }
        interpInput.fill(0f); interpOutput.fill(0f)
        lineWrite = 0L; preDelayWrite = 0; modPhase = 0f
        lowCutL.reset(); lowCutR.reset(); highCutL.reset(); highCutR.reset()
        tiltL.reset(); tiltR.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Tempo-locked, filtered, ducking delay. Feedback is band-limited so echoes
 * never muddy the low end, and the duck keeps the tail out of the way while the
 * singer is still delivering the line.
 */
class TempoDelayModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.TEMPO_DELAY
    override var enabled = true
    private val maxDelay = (sampleRate * 2.5f).toInt() + 8
    private val bufferL = FloatArray(maxDelay)
    private val bufferR = FloatArray(maxDelay)
    private var writeIndex = 0
    private var delayFramesL = 1
    private var delayFramesR = 1
    private var feedbackAmount = 0.25f
    private val hpL = Biquad(sampleRate); private val hpR = Biquad(sampleRate)
    private val lpL = Biquad(sampleRate); private val lpR = Biquad(sampleRate)
    private var pingPong = false
    private var ducking = true
    private var duckGain = 1f
    private val duckRelease = DynMath.coeffMs(180f, sampleRate)
    private val mixSmoother = SmoothValue(0.25f, sampleRate, 18f)
    private var tempoBpm = 120f
    private var division = 1f
    private var freeTimeMs = 110f
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak
    // Wet/dry insert: the dry path is not delayed, so this is not latency.
    override val latencySamples: Int get() = 0

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        division = slot.value("division")
        freeTimeMs = slot.value("timeMs")
        feedbackAmount = slot.value("feedback")
        pingPong = slot.value("pingPong") >= 0.5f
        ducking = slot.value("ducking") >= 0.5f
        mixSmoother.set(slot.value("mix"))
        hpL.setHighPass(slot.value("hpHz"), 0.707f); hpR.setHighPass(slot.value("hpHz"), 0.707f)
        lpL.setLowPass(slot.value("lpHz"), 0.707f); lpR.setLowPass(slot.value("lpHz"), 0.707f)
        updateTimes()
    }

    override fun setTempoBpm(bpm: Float) {
        tempoBpm = bpm.coerceIn(20f, 300f)
        updateTimes()
    }

    private fun updateTimes() {
        val beatMs = 60000f / tempoBpm
        val ms = when (division.toInt()) {
            0 -> beatMs / 4f          // 1/16
            1 -> beatMs / 2f          // 1/8
            2 -> beatMs * 0.75f       // 1/8 dotted
            3 -> beatMs               // 1/4
            4 -> beatMs * 1.5f        // 1/4 dotted
            5 -> beatMs * 2f          // 1/2
            else -> freeTimeMs        // slapback / free
        }
        delayFramesL = ((ms / 1000f) * sampleRate).toInt().coerceIn(1, maxDelay - 4)
        delayFramesR = if (pingPong) delayFramesL
        else ((ms * 1.02f / 1000f) * sampleRate).toInt().coerceIn(1, maxDelay - 4)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val readL = (writeIndex - delayFramesL + maxDelay) % maxDelay
        val readR = (writeIndex - delayFramesR + maxDelay) % maxDelay
        val delayedL = bufferL[readL]
        val delayedR = bufferR[readR]
        val fbL = lpL.process(hpL.process(delayedL))
        val fbR = lpR.process(hpR.process(delayedR))
        if (pingPong) {
            bufferL[writeIndex] = inL + fbR * feedbackAmount
            bufferR[writeIndex] = inR + fbL * feedbackAmount
        } else {
            bufferL[writeIndex] = inL + fbL * feedbackAmount
            bufferR[writeIndex] = inR + fbR * feedbackAmount
        }
        writeIndex = (writeIndex + 1) % maxDelay

        if (ducking) {
            val dry = maxOf(abs(inL), abs(inR))
            val target = if (dry > Db.toLinear(-30f)) 0.35f else 1f
            duckGain = if (target < duckGain) target
            else target + duckRelease * (duckGain - target)
        } else {
            duckGain = 1f
        }

        val mix = mixSmoother.next()
        val wetL = delayedL * duckGain
        val wetR = delayedR * duckGain
        out[0] = inL * (1f - mix) + wetL * mix
        out[1] = inR * (1f - mix) + wetR * mix
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        bufferL.fill(0f); bufferR.fill(0f); writeIndex = 0; duckGain = 1f
        hpL.reset(); hpR.reset(); lpL.reset(); lpR.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Chorus / width. Modes 0 and 1 are the two Juno chorus settings (I subtle,
 * II the famous wide detune); mode 2 is a Haas/doubler delay for vocals and
 * mono sources that need width without reverb.
 */
class ChorusWidthModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.CHORUS_WIDTH
    override var enabled = true
    private val maxDelay = (sampleRate * 0.06f).toInt() + 8
    private val bufferL = FloatArray(maxDelay)
    private val bufferR = FloatArray(maxDelay)
    private var writeIndex = 0
    private var phase = 0f
    private var mode = 1
    private var rateHz = 0.8f
    private var depthMs = 3.5f
    private var width = 0.6f
    private val mixSmoother = SmoothValue(0.35f, sampleRate, 18f)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        mode = slot.value("mode").toInt().coerceIn(0, 2)
        rateHz = slot.value("rateHz")
        depthMs = slot.value("depthMs")
        width = slot.value("width")
        mixSmoother.set(slot.value("mix"))
    }

    private fun read(delayFrames: Float, buffer: FloatArray): Float {
        var pos = (writeIndex - delayFrames) % maxDelay
        if (pos < 0f) pos += maxDelay
        val i0 = pos.toInt()
        val frac = pos - i0
        val i1 = (i0 + 1) % maxDelay
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        phase += 2f * PI.toFloat() * rateHz / sampleRate
        if (phase >= 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
        val depthScale = when (mode) {
            0 -> 0.45f
            1 -> 1.0f
            else -> 1.4f
        }
        val baseMs = when (mode) {
            0 -> 8f
            1 -> 12f
            else -> 18f
        }
        val depthFrames = depthMs * depthScale * sampleRate / 1000f
        val baseFrames = baseMs * sampleRate / 1000f
        val delayL = baseFrames + (0.5f + 0.5f * sin(phase)) * depthFrames
        val delayR = baseFrames + (0.5f + 0.5f * sin(phase + PI.toFloat() * 0.8f)) * depthFrames
        val wetL = read(delayL, bufferL)
        val wetR = read(delayR, bufferR)
        bufferL[writeIndex] = inL
        bufferR[writeIndex] = inR
        writeIndex = (writeIndex + 1) % maxDelay

        val mix = mixSmoother.next()
        val blendedL = inL + (wetL - inL) * mix
        val blendedR = inR + (wetR - inR) * mix
        val mid = (blendedL + blendedR) * 0.5f
        val side = (blendedL - blendedR) * 0.5f * (0.6f + width)
        out[0] = mid + side
        out[1] = mid - side
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        bufferL.fill(0f); bufferR.fill(0f); writeIndex = 0; phase = 0f
        inputMeter.reset(); outputMeter.reset()
    }
}
