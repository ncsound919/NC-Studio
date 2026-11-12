package com.overlay.audio.plugins

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Dynamics modules modeled on documented hardware behaviour:
 *
 * - 1176: 20-800 us attack, 50-1100 ms release, hard knee, 4/8/12/20:1,
 *   program-dependent release (fast after transients, slower after sustained
 *   level) and an all-buttons-in circuit state that is more than a ratio: it
 *   raises distortion, slows release and bends the ratio toward 12-20:1.
 * - LA-2A: ~3:1 compress / limiting in Limit, ~10 ms attack, a two-stage
 *   program-dependent release (about 60 ms to recover half the reduction,
 *   then seconds), tube warmth kept separate from the optical gain action.
 * - SSL G bus compressor: 2/4/10:1, 0.1-30 ms attack, fixed 100/300/600/1200 ms
 *   or multi-stage Auto release, stereo-linked and a feedback sidechain that
 *   reads the post-VCA signal, which is where the "glue" comes from.
 * - Vari-mu: variable ratio that stiffens with gain reduction plus the six
 *   documented 670 time-constant positions, two of them program-dependent.
 */
/**
 * Rectified peak detector with an instantaneous attack and a short hold: the
 * sidechain stage of a real compressor. The gain computer has to see the
 * programme level *between* waveform peaks - feeding it instantaneous sample
 * values makes the gain reduction ripple at the signal frequency and makes
 * every release measurement depend on where in the cycle you stopped looking.
 */
internal class PeakEnvelope(private val sampleRate: Float, holdMs: Float = 15f) {
    private val decay = DynMath.coeffMs(holdMs, sampleRate)
    private var value = 0f

    fun update(x: Float): Float {
        val rectified = abs(x)
        value = if (rectified > value) rectified else value * decay
        return value
    }

    fun reset() { value = 0f }
}

internal object DynMath {
    fun coeffMs(ms: Float, sampleRate: Float): Float =
        exp(-1.0 / ((ms.coerceAtLeast(0.005f)) * sampleRate / 1000.0)).toFloat()

    /**
     * Turn a per-sample coefficient into the matching step size.
     *
     * A one-pole follower is `x += (1 - coeff) * (target - x)`; using `coeff`
     * itself as the step makes the follower snap to the target in a single
     * sample, which silently disables every slow detector built on it.
     */
    fun smoothingStep(coeff: Float): Float = (1f - coeff).coerceIn(0f, 1f)

    fun levelDb(peak: Float): Float = if (peak > 1e-6f) 20f * log10(peak) else -120f

    /** Soft-knee gain computer returning target gain reduction in dB. */
    fun computeGainReductionDb(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
        val delta = levelDb - thresholdDb
        val half = kneeDb * 0.5f
        val slope = 1f - 1f / ratio.coerceAtLeast(1f)
        return when {
            delta <= -half -> 0f
            delta >= half -> slope * delta
            else -> {
                val t = (delta + half) / kneeDb.coerceAtLeast(0.0001f)
                slope * (t * t * half)
            }
        }
    }
}

/**
 * UREI 1176. FET, hard knee, ultra-fast attack, and a release that speeds up
 * after brief transients but slows down after sustained level.
 */
class Fet1176Module(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.FET_1176
    override var enabled = true
    private var drive = 1f
    private var output = 1f
    private var ratio = 4f
    private var allButtons = false
    private var thresholdDb = -18f
    private var attackCoeff = DynMath.coeffMs(0.2f, sampleRate)
    private var releaseFastCoeff = DynMath.coeffMs(120f, sampleRate)
    private var releaseSlowCoeff = DynMath.coeffMs(500f, sampleRate)
    private var grDb = 0f
    private var sustain = 0f
    private val detector = PeakEnvelope(sampleRate)
    private val sustainCoeff = DynMath.coeffMs(400f, sampleRate)
    private var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    private var oversampler: Oversampler? = null
    private val scratch = FloatArray(2)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        drive = Db.toLinear(slot.value("inputDb"))
        output = Db.toLinear(slot.value("outputDb"))
        ratio = slot.value("ratio")
        allButtons = slot.value("allButtons") >= 0.5f
        attackCoeff = DynMath.coeffMs(slot.value("attackUs") / 1000f, sampleRate)
        val release = slot.value("releaseMs")
        // All-buttons-in also alters the release circuit; a well-known side
        // effect is the slow, breathing recovery.
        val releaseScale = if (allButtons) 1.8f else 1f
        releaseFastCoeff = DynMath.coeffMs(release * releaseScale, sampleRate)
        releaseSlowCoeff = DynMath.coeffMs(release * releaseScale * 3.5f, sampleRate)
        mixSmoother.set(slot.value("mix"))
        // The 1176 clamps hard, so the harmonic stage is oversampled.
        val factor = if (allButtons) 4 else 2
        if (oversampler == null || oversampler?.factor != factor) oversampler = Oversampler(factor, sampleRate)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val dl = inL * drive
        val dr = inR * drive
        val peak = detector.update(max(abs(dl), abs(dr)))
        val level = DynMath.levelDb(peak)
        val effectiveRatio = if (allButtons) (ratio.coerceAtLeast(12f)) else ratio
        val target = DynMath.computeGainReductionDb(level, thresholdDb, effectiveRatio, 0f)
        // Program dependence: brief peaks recover quickly, sustained level does not.
        sustain = sustain + DynMath.smoothingStep(sustainCoeff) * (target - sustain)
        val coeff = if (target > grDb) attackCoeff else {
            val sustained = (sustain / 12f).coerceIn(0f, 1f)
            fastReleaseBlend(releaseFastCoeff, releaseSlowCoeff, sustained)
        }
        grDb = target + coeff * (grDb - target)
        val gain = Db.toLinear(-grDb)
        var l = dl * gain
        var r = dr * gain
        val os = oversampler
        if (os != null) {
            val edge = (grDb / 18f).coerceIn(0f, 1f) * (if (allButtons) 1f else 0.45f)
            scratch[0] = l; scratch[1] = r
            os.upsample(scratch[0], scratch[1])
            for (i in 0 until os.factor) {
                os.bufferL[i] = fetClamp(os.bufferL[i], edge)
                os.bufferR[i] = fetClamp(os.bufferR[i], edge)
            }
            os.downsample(scratch)
            l = scratch[0]; r = scratch[1]
        }
        l *= output / drive
        r *= output / drive
        val mix = mixSmoother.next()
        val dryL = inL
        val dryR = inR
        out[0] = dryL + (l - dryL) * mix
        out[1] = dryR + (r - dryR) * mix
        outputMeter.update(out[0], out[1])
    }

    private fun fetClamp(x: Float, amount: Float): Float {
        if (amount <= 0.001f) return x
        val driven = x * (1f + amount * 3f)
        return (tanh(driven) * (1f - amount * 0.15f) + x * amount * 0.15f)
    }

    private fun fastReleaseBlend(fast: Float, slow: Float, blend: Float): Float =
        fast + (slow - fast) * blend.coerceIn(0f, 1f)

    override fun reset() {
        grDb = 0f; sustain = 0f; detector.reset(); oversampler?.reset()
        inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Teletronix LA-2A. The T4 cell's light-dependent resistance gives a leveling
 * action with no ratio control (~3:1), a soft knee, and a release that recovers
 * half quickly and then takes seconds. The tube stage adds gentle color.
 */
class OptoLa2aModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.OPTO_LA2A
    override var enabled = true
    private var drive = 1f
    private var makeup = 1f
    private var limitMode = false
    private var thresholdDb = -18f
    private var attackCoeff = DynMath.coeffMs(10f, sampleRate)
    private var fastRelease = DynMath.coeffMs(60f, sampleRate)
    private var slowRelease = DynMath.coeffMs(1500f, sampleRate)
    private var grDb = 0f
    private var peakGr = 0f
    private val detector = PeakEnvelope(sampleRate)
    private var slowDecayCoeff = DynMath.coeffMs(4000f, sampleRate)
    private var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        // Peak reduction drives the T4 cell: more light, more reduction.
        drive = Db.toLinear(-6f + slot.value("peakReduction") * 1.6f)
        makeup = Db.toLinear(slot.value("gainDb"))
        limitMode = slot.value("limitMode") >= 0.5f
        thresholdDb = if (limitMode) -12f else -20f
        // Heavier or longer reduction releases more slowly; the slow stage
        // stretches with the peak reduction that has been reached.
        slowRelease = DynMath.coeffMs(1500f + peakGr * 400f, sampleRate)
        mixSmoother.set(slot.value("mix"))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val dl = inL * drive
        val dr = inR * drive
        val peak = detector.update(max(abs(dl), abs(dr)))
        val level = DynMath.levelDb(peak)
        val ratio = if (limitMode) 20f else 3f
        val knee = if (limitMode) 4f else 8f
        val target = DynMath.computeGainReductionDb(level, thresholdDb, ratio, knee)
        val coeff = if (target > grDb) attackCoeff else {
            // Two-stage optical release: fast half-recovery, then the long tail.
            if (grDb > peakGr * 0.5f) fastRelease else slowRelease
        }
        grDb = target + coeff * (grDb - target)
        peakGr = max(peakGr, grDb)
        peakGr *= slowDecayCoeff
        val gain = Db.toLinear(-grDb) * makeup
        var l = dl * gain
        var r = dr * gain
        // Tube amp: gentle asymmetric second harmonic, not a distortion stage.
        l = l - 0.03f * l * l
        r = r - 0.03f * r * r
        val mix = mixSmoother.next()
        out[0] = inL + (l - inL) * mix
        out[1] = inR + (r - inR) * mix
        outputMeter.update(out[0], out[1])
    }

    override fun reset() { grDb = 0f; peakGr = 0f; detector.reset(); inputMeter.reset(); outputMeter.reset() }
}

/**
 * SSL 4000 G series bus compressor. A feedback design whose sidechain VCA
 * reads the post-gain-reduction signal, a stereo-linked detector that follows
 * the louder channel, and a multi-stage Auto release that lengthens as the mix
 * pushes it.
 */
class VcaBusCompModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.VCA_BUS_COMP
    override var enabled = true
    private var thresholdDb = -10f
    private var ratio = 2f
    private var attackCoeff = DynMath.coeffMs(10f, sampleRate)
    private var release100 = DynMath.coeffMs(100f, sampleRate)
    private var release600 = DynMath.coeffMs(600f, sampleRate)
    private var release1200 = DynMath.coeffMs(1200f, sampleRate)
    private var fixedReleaseCoeff = DynMath.coeffMs(300f, sampleRate)
    private var autoRelease = true
    private var makeup = 1f
    private var grDb = 0f
    private var stage = 0
    private var stageTimer = 0
    private var lastOutL = 0f
    private var lastOutR = 0f
    private val detector = PeakEnvelope(sampleRate, holdMs = 20f)
    private var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        thresholdDb = slot.value("thresholdDb")
        ratio = slot.value("ratio")
        attackCoeff = DynMath.coeffMs(slot.value("attackMs"), sampleRate)
        autoRelease = slot.value("autoRelease") >= 0.5f
        fixedReleaseCoeff = DynMath.coeffMs(slot.value("releaseMs"), sampleRate)
        makeup = Db.toLinear(slot.value("makeupDb"))
        mixSmoother.set(slot.value("mix"))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        // Feedback topology: the detector sees the signal after the VCA, and
        // holds the peak between cycles so the gain sits still instead of
        // rippling with the waveform.
        val peak = detector.update(max(abs(lastOutL), abs(lastOutR)))
        val level = DynMath.levelDb(peak)
        val target = DynMath.computeGainReductionDb(level, thresholdDb, ratio, 2f)
        // The auto-release stage follows the gain reduction that is actually in
        // effect (a smooth signal), not the instantaneous target, which drops
        // to zero between peaks and would collapse the stage back to the fast
        // time constant mid-tail.
        val releaseCoeff = if (autoRelease) autoReleaseCoeff(grDb) else fixedReleaseCoeff
        val coeff = if (target > grDb) attackCoeff else releaseCoeff
        grDb = target + coeff * (grDb - target)
        val gain = Db.toLinear(-grDb) * makeup
        val wetL = inL * gain
        val wetR = inR * gain
        lastOutL = wetL
        lastOutR = wetR
        val mix = mixSmoother.next()
        out[0] = inL + (wetL - inL) * mix
        out[1] = inR + (wetR - inR) * mix
        outputMeter.update(out[0], out[1])
    }

    /**
     * Auto release is program dependent rather than a single time constant:
     * light reduction returns in ~100 ms, moderate in ~600 ms and sustained
     * heavy reduction in ~1.2 s.
     */
    private fun autoReleaseCoeff(currentGrDb: Float): Float {
        if (currentGrDb > 1.5f) {
            stageTimer = 0
            stage = when {
                currentGrDb > 6f -> 2
                currentGrDb > 3f -> 1
                else -> 0
            }
        } else {
            stageTimer++
            // Hold the longer stage for a second and a half so the release does
            // not collapse back to the fast stage mid-tail.
            if (stageTimer > sampleRate.toInt() * 3 / 2 && stage > 0) stage--
        }
        return when (stage) {
            0 -> release100
            1 -> release600
            else -> release1200
        }
    }

    override fun reset() {
        grDb = 0f; stage = 0; stageTimer = 0; lastOutL = 0f; lastOutR = 0f
        detector.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Vari-mu tube compressor (Fairchild 670 character). Ratio stiffens with gain
 * reduction, the knee widens with the DC threshold, and the six documented
 * time constants include two program-dependent positions.
 */
class VariMuCompModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.VARI_MU_COMP
    override var enabled = true
    private var drive = 1f
    private var makeup = 1f
    private var dcThresholdDb = -12f
    private var ratio = 2.5f
    private var attackCoeff = DynMath.coeffMs(0.4f, sampleRate)
    private var releaseCoeff = DynMath.coeffMs(2000f, sampleRate)
    private var programLongCoeff = DynMath.coeffMs(10000f, sampleRate)
    private var programDependent = false
    private var grDb = 0f
    private var grHistory = 0f
    private val detector = PeakEnvelope(sampleRate)
    private val historyCoeff = DynMath.coeffMs(2500f, sampleRate)
    private var mixSmoother = SmoothValue(1f, sampleRate, 12f)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        drive = Db.toLinear(slot.value("driveDb"))
        makeup = Db.toLinear(slot.value("makeupDb"))
        dcThresholdDb = slot.value("thresholdDb")
        ratio = slot.value("ratio")
        val tc = slot.value("timeConstant").toInt().coerceIn(0, 5)
        val attackMs: Float
        val releaseMs: Float
        when (tc) {
            0 -> { attackMs = 0.2f; releaseMs = 300f; programDependent = false }
            1 -> { attackMs = 0.2f; releaseMs = 800f; programDependent = false }
            2 -> { attackMs = 0.4f; releaseMs = 2000f; programDependent = false }
            3 -> { attackMs = 0.8f; releaseMs = 5000f; programDependent = false }
            4 -> { attackMs = 0.2f; releaseMs = 2000f; programDependent = true }
            else -> { attackMs = 0.4f; releaseMs = 300f; programDependent = true }
        }
        attackCoeff = DynMath.coeffMs(attackMs, sampleRate)
        releaseCoeff = DynMath.coeffMs(releaseMs, sampleRate)
        programLongCoeff = DynMath.coeffMs(if (tc == 5) 25000f else 10000f, sampleRate)
        mixSmoother.set(slot.value("mix"))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val dl = inL * drive
        val dr = inR * drive
        val peak = detector.update(max(abs(dl), abs(dr)))
        val level = DynMath.levelDb(peak)
        // Variable mu: the effective ratio stiffens as the tube is pushed.
        val effectiveRatio = ratio * (1f + (grDb / 12f))
        val knee = (dcThresholdDb * -0.5f + 6f).coerceIn(2f, 14f)
        val target = DynMath.computeGainReductionDb(level, dcThresholdDb, effectiveRatio, knee)
        val release = if (programDependent && grHistory > 4f) programLongCoeff else releaseCoeff
        val coeff = if (target > grDb) attackCoeff else release
        grDb = target + coeff * (grDb - target)
        grHistory = grHistory + DynMath.smoothingStep(historyCoeff) * (grDb - grHistory)
        val gain = Db.toLinear(-grDb) * makeup
        var l = dl * gain
        var r = dr * gain
        // Tube push-pull stage: mostly second harmonic with a little third.
        l = l - 0.08f * l * l + 0.02f * l * l * l
        r = r - 0.08f * r * r + 0.02f * r * r * r
        val mix = mixSmoother.next()
        out[0] = inL + (l - inL) * mix
        out[1] = inR + (r - inR) * mix
        outputMeter.update(out[0], out[1])
    }

    override fun reset() { grDb = 0f; grHistory = 0f; detector.reset(); inputMeter.reset(); outputMeter.reset() }
}

/** NY-style parallel compressor: crush hard, then blend under the dry signal. */
class ParallelCompModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.PARALLEL_COMP
    override var enabled = true
    private var thresholdDb = -22f
    private var ratio = 8f
    private var attackCoeff = DynMath.coeffMs(5f, sampleRate)
    private var releaseCoeff = DynMath.coeffMs(120f, sampleRate)
    private var blendSmoother = SmoothValue(0.35f, sampleRate, 15f)
    private var makeup = 1f
    private var grDb = 0f
    private val detector = PeakEnvelope(sampleRate)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        thresholdDb = slot.value("thresholdDb")
        ratio = slot.value("ratio")
        attackCoeff = DynMath.coeffMs(slot.value("attackMs"), sampleRate)
        releaseCoeff = DynMath.coeffMs(slot.value("releaseMs"), sampleRate)
        blendSmoother.set(slot.value("blend"))
        makeup = Db.toLinear(slot.value("makeupDb"))
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val peak = detector.update(max(abs(inL), abs(inR)))
        val level = DynMath.levelDb(peak)
        val target = DynMath.computeGainReductionDb(level, thresholdDb, ratio, 0f)
        val coeff = if (target > grDb) attackCoeff else releaseCoeff
        grDb = target + coeff * (grDb - target)
        val gain = Db.toLinear(-grDb) * makeup
        val blend = blendSmoother.next()
        out[0] = inL + (inL * gain - inL) * blend
        out[1] = inR + (inR * gain - inR) * blend
        outputMeter.update(out[0], out[1])
    }

    override fun reset() { grDb = 0f; detector.reset(); inputMeter.reset(); outputMeter.reset() }
}

/**
 * Split-band de-esser. The sibilance band is detected and only that band is
 * attenuated, so "s" sounds lose harshness without dulling the whole vocal.
 */
class DeEsserModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.DE_ESSER
    override var enabled = true
    private val bandL = Biquad(sampleRate)
    private val bandR = Biquad(sampleRate)
    private val bandLowL = Biquad(sampleRate)
    private val bandLowR = Biquad(sampleRate)
    private var thresholdDb = -22f
    private var maxReductionDb = 3f
    private var splitBand = true
    private var grDb = 0f
    private val detector = PeakEnvelope(sampleRate, holdMs = 8f)
    private var attackCoeff = DynMath.coeffMs(3f, sampleRate)
    private var releaseCoeff = DynMath.coeffMs(45f, sampleRate)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override    fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        thresholdDb = slot.value("thresholdDb")
        maxReductionDb = slot.value("amountDb")
        splitBand = slot.value("split") >= 0.5f
        val freq = slot.value("freqHz")
        // Detection band: the sibilance region emphasised so the detector
        // triggers on "s" energy rather than on the vocal's body.
        bandL.setPeaking(freq, 12f, 0.9f)
        bandR.setPeaking(freq, 12f, 0.9f)
        // Crossover: the low band is what the split keeps untouched, so the
        // sibilance band is everything above it (input - low band).
        bandLowL.setLowPass(freq * 0.55f, 0.707f)
        bandLowR.setLowPass(freq * 0.55f, 0.707f)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val mono = (inL + inR) * 0.5f
        val band = detector.update(bandL.process(mono))
        val level = DynMath.levelDb(band)
        val over = (level - thresholdDb).coerceAtLeast(0f)
        val target = over.coerceAtMost(maxReductionDb * 2f)
        val coeff = if (target > grDb) attackCoeff else releaseCoeff
        grDb = target + coeff * (grDb - target)
        val reduction = grDb.coerceAtMost(maxReductionDb)
        val bandGain = Db.toLinear(-reduction)
        if (splitBand) {
            val lowMonoL = bandLowL.process(inL)
            val lowMonoR = bandLowR.process(inR)
            val sibL = inL - lowMonoL
            val sibR = inR - lowMonoR
            out[0] = lowMonoL + sibL * bandGain
            out[1] = lowMonoR + sibR * bandGain
        } else {
            val gain = Db.toLinear(-reduction * 0.6f)
            out[0] = inL * gain
            out[1] = inR * gain
        }
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        bandL.reset(); bandR.reset(); bandLowL.reset(); bandLowR.reset()
        grDb = 0f; detector.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * Sidechain duck: the engine feeds the key input from the selected source
 * track (normally the kick), and the module ducks the destination (bass, 808,
 * pads) so both can share the low end without fighting.
 */
class SidechainDuckModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.SIDECHAIN_DUCK
    override var enabled = true
    private var thresholdDb = -20f
    private var ratio = 6f
    private var attackCoeff = DynMath.coeffMs(5f, sampleRate)
    private var releaseCoeff = DynMath.coeffMs(120f, sampleRate)
    private var floorDb = -12f
    private var grDb = 0f
    private var keyL = 0f
    private var keyR = 0f
    private val detector = PeakEnvelope(sampleRate, holdMs = 25f)
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        thresholdDb = slot.value("thresholdDb")
        ratio = slot.value("ratio")
        attackCoeff = DynMath.coeffMs(slot.value("attackMs"), sampleRate)
        releaseCoeff = DynMath.coeffMs(slot.value("releaseMs"), sampleRate)
        floorDb = slot.value("floorDb")
    }

    override fun setKey(l: Float, r: Float) { keyL = l; keyR = r }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val keyPeak = detector.update(max(abs(keyL), abs(keyR)))
        val level = DynMath.levelDb(keyPeak)
        val target = DynMath.computeGainReductionDb(level, thresholdDb, ratio, 3f)
            .coerceAtMost(-floorDb)
        val coeff = if (target > grDb) attackCoeff else releaseCoeff
        grDb = target + coeff * (grDb - target)
        val gain = Db.toLinear(-grDb)
        out[0] = inL * gain
        out[1] = inR * gain
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        grDb = 0f; keyL = 0f; keyR = 0f
        detector.reset(); inputMeter.reset(); outputMeter.reset()
    }
}

/**
 * True-peak limiter. Detection and gain application run oversampled so
 * inter-sample peaks are caught instead of clipping in the DAC; an exact
 * clamp backs up the ceiling for whatever the gain computer let through.
 *
 * The clamp is deliberately a hard ceiling rather than a soft curve. A soft
 * curve that eases in from *above* the knee (the usual
 * `ceiling + u / (1 + k*u)`) approaches `ceiling + 1/k`, so a limiter set to
 * -1 dBTP could still emit +1.15 dBFS - it would miss its own specification
 * on exactly the material it exists to catch. Nothing leaves this module
 * above `ceiling`.
 *
 * The clamp runs twice, and both stages are load-bearing. Clamping the
 * oversampled signal bounds the waveform between base-rate samples, which is
 * what "true peak" means; clamping the decimated samples bounds what the DAC
 * is actually handed, because the Butterworth decimator is minimum-phase and
 * rings about 22% past a hard-clamped input on transient material.
 */
class TruePeakLimiterModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.TRUE_PEAK_LIMITER
    override var enabled = true
    private var ceiling = Db.toLinear(-1f)
    private var releaseCoeff = DynMath.coeffMs(120f, sampleRate)
    private var oversampler: Oversampler? = null
    private var gain = 1f
    private var grDb = 0f
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    // Lookahead so the limiter reacts to a peak before it reaches the output.
    private val lookAhead = (sampleRate * 0.0015f).toInt().coerceAtLeast(1)
    private val delayL = FloatArray(lookAhead)
    private val delayR = FloatArray(lookAhead)
    private var delayPos = 0
    private var count = 0

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak
    override val latencySamples: Int get() = lookAhead

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        ceiling = Db.toLinear(slot.value("ceilingDb"))
        releaseCoeff = DynMath.coeffMs(slot.value("releaseMs"), sampleRate)
        val factor = slot.value("oversample").toInt().coerceIn(1, 4)
        if (factor <= 1) {
            oversampler = null
        } else if (oversampler == null || oversampler?.factor != factor) {
            oversampler = Oversampler(factor, sampleRate)
        }
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)

        // Future peak drives the gain for the delayed (current output) sample.
        val futurePeak = max(abs(inL), abs(inR))
        val os = oversampler
        val targetGain = if (futurePeak * gain > ceiling)
            ceiling / futurePeak.coerceAtLeast(1e-6f) else 1f
        gain = if (targetGain < gain) targetGain else targetGain + releaseCoeff * (gain - targetGain)

        val delayedL = delayL[delayPos]
        val delayedR = delayR[delayPos]
        delayL[delayPos] = inL
        delayR[delayPos] = inR
        delayPos = (delayPos + 1) % lookAhead
        if (count < lookAhead) count++

        if (count < lookAhead) {
            out[0] = 0f; out[1] = 0f
            grDb = -DynMath.levelDb(gain)
            outputMeter.update(out[0], out[1])
            return
        }

        if (os != null) {
            os.upsample(delayedL, delayedR)
            for (i in 0 until os.factor) {
                os.bufferL[i] = limitToCeiling(os.bufferL[i] * gain)
                os.bufferR[i] = limitToCeiling(os.bufferR[i] * gain)
            }
            os.downsample(out)
            // The decimator's step response overshoots a hard-clamped input,
            // so the ceiling has to be re-asserted on what is really handed to
            // the converter - otherwise a -1 dBTP setting still emits over
            // 0 dBFS on transient material.
            out[0] = limitToCeiling(out[0])
            out[1] = limitToCeiling(out[1])
        } else {
            out[0] = limitToCeiling(delayedL * gain)
            out[1] = limitToCeiling(delayedR * gain)
        }
        grDb = -DynMath.levelDb(gain)
        outputMeter.update(out[0], out[1])
    }

    /** Final safety net: never emit a sample above the configured ceiling. */
    private fun limitToCeiling(x: Float): Float {
        return when {
            x > ceiling -> ceiling
            x < -ceiling -> -ceiling
            else -> x
        }
    }

    override fun reset() {
        gain = 1f; grDb = 0f; oversampler?.reset()
        delayL.fill(0f); delayR.fill(0f); delayPos = 0; count = 0
        inputMeter.reset(); outputMeter.reset()
    }
}

/** Simple brickwall channel limiter for taming a single track. */
class ChannelLimiterModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.CHANNEL_LIMITER
    override var enabled = true
    private var ceiling = Db.toLinear(-0.5f)
    private var releaseCoeff = DynMath.coeffMs(30f, sampleRate)
    private var gain = 1f
    private var grDb = 0f
    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    override val gainReductionDb: Float get() = grDb
    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        ceiling = Db.toLinear(slot.value("ceilingDb"))
        releaseCoeff = DynMath.coeffMs(slot.value("releaseMs"), sampleRate)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        inputMeter.update(inL, inR)
        val peak = max(abs(inL), abs(inR))
        val targetGain = if (peak * gain > ceiling) ceiling / peak.coerceAtLeast(1e-6f) else 1f
        gain = if (targetGain < gain) targetGain else targetGain + releaseCoeff * (gain - targetGain)
        out[0] = inL * gain
        out[1] = inR * gain
        grDb = -DynMath.levelDb(gain)
        outputMeter.update(out[0], out[1])
    }

    override fun reset() { gain = 1f; grDb = 0f; inputMeter.reset(); outputMeter.reset() }
}
