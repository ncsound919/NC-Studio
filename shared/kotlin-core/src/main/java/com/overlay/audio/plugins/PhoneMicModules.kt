package com.overlay.audio.plugins

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phone mic specialized modules.
 *
 * Tailored noise reduction and mic emulation for phone recordings.
 * Based on deep research: RNNoise, SpeexDSP, WebRTC, PMC 12379677.
 */

/**
 * PHONE_NOISE_REDUCTION - broadband downward-expanding noise gate.
 *
 * A phone mic records the room as well as the voice, and room noise sits in
 * exactly the same bands as the programme, so a broadband processor cannot
 * subtract it without a transform. What it can do - what every gate and
 * downward expander does - is push the *quiet* material down: below
 * [thresholdDb] the gain walks towards [reductionDb] of full attenuation and
 * stays open on anything at or above the threshold.
 *
 * The threshold is explicit rather than learned. The earlier design tracked
 * an adaptive noise floor, and a floor that follows its own input converges
 * on the signal itself - after long enough every sustained quiet instrument is
 * indistinguishable from the noise it was meant to remove, so the gate closes
 * on it. That is the classic adaptive-gate failure, and an explicit
 * threshold is how real gates avoid it.
 *
 * The two detectors the research called for are present and now do work:
 *
 * - [windDetection] measures how dominant the sub-150 Hz band is and
 *   subtracts that band in proportion, so wind (an LF burst) comes out
 *   without thinning the voice.
 * - [handlingDetection] ducks a transient standing more than 9.5 dB above the
 *   recent peak: knocks, cable thumps and handling noise are impulses, not a
 *   level problem.
 *
 * [smoothingMs] sets the envelope release, the gain smoothing and - times ten
 * - the peak hold the knock detector measures against, so one control moves
 * the whole detector rather than one corner of it.
 *
 * Stereo is linked: one gain for both channels, so the image never wanders as
 * the gate opens and closes. No allocation and no random source in the audio
 * path - this module is covered by the shared golden parity vectors.
 */
class PhoneNoiseReductionModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.PHONE_NOISE_REDUCTION
    override var enabled = true

    private var reductionDb = 12f
    private var thresholdDb = -35f
    private var smoothingMs = 100f
    private var windDetection = true
    private var handlingDetection = true

    // Linked broadband detector.
    private var env = 0f
    private var gain = 1f

    // Wind: a fixed low-pass whose output is subtracted in proportion.
    private val windLpL = Biquad(sampleRate)
    private val windLpR = Biquad(sampleRate)
    private var lfEnv = 0f
    private var fullEnv = 0f
    private var windAmount = 0f

    // Handling: a peak-hold reference and a decaying duck.
    private var handlingRef = 0f
    private var duck = 0f

    // Control coefficients, rebuilt in configure().
    private var envRelease = 1f
    private var gainSmooth = 1f
    private var refHold = 1f
    private var duckRelease = 1f
    private var windSmooth = 1f
    private var minGain = 1f
    private var thresholdLinear = 0f
    private var windDepth = 0f
    private var duckDepth = 0f

    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    init {
        windLpL.setLowPass(WIND_CUTOFF_HZ, 0.7071f)
        windLpR.setLowPass(WIND_CUTOFF_HZ, 0.7071f)
    }

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak
    override val gainReductionDb: Float get() = -DynMath.levelDb(gain)

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        reductionDb = slot.value("reductionDb")
        thresholdDb = slot.value("thresholdDb")
        smoothingMs = slot.value("smoothingMs")
        windDetection = slot.value("windDetection") >= 0.5f
        handlingDetection = slot.value("handlingDetection") >= 0.5f

        minGain = Db.toLinear(-reductionDb)
        thresholdLinear = Db.toLinear(thresholdDb)
        windDepth = (1f - minGain).coerceIn(0f, 0.9f)
        duckDepth = (1f - sqrt(minGain)).coerceIn(0f, 0.9f)
        envRelease = DynMath.coeffMs(smoothingMs, sampleRate)
        gainSmooth = DynMath.coeffMs(smoothingMs * 0.25f, sampleRate)
        refHold = DynMath.coeffMs(smoothingMs, sampleRate)
        duckRelease = DynMath.coeffMs(minOf(smoothingMs, 80f), sampleRate)
        windSmooth = DynMath.coeffMs(smoothingMs, sampleRate)
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        if (!enabled) { out[0] = inL; out[1] = inR; return }
        val peak = maxOf(abs(inL), abs(inR))
        inputMeter.update(peak, peak)

        // Broadband gate: open at the threshold, fully reduced 12 dB below it.
        env = maxOf(peak, env * envRelease)
        val ratio = (env / thresholdLinear).coerceIn(0f, 1f)
        val closing = ((1f - ratio) / (1f - CLOSE_AT)).coerceIn(0f, 1f)
        val targetGain = 1f - closing * (1f - minGain)
        gain = targetGain + gainSmooth * (gain - targetGain)

        // A knock stands well above the recent peak; duck it, then let it go.
        duck *= duckRelease
        if (handlingDetection && handlingRef > 1e-4f && peak > handlingRef * KNOCK_RATIO) {
            duck = duckDepth
        }
        handlingRef = maxOf(peak, handlingRef * refHold)

        // Wind is an LF burst, so subtract the LF band in proportion to how
        // much of the signal it owns rather than gating the whole signal.
        val lpL = windLpL.process(inL)
        val lpR = windLpR.process(inR)
        val lpMono = abs((lpL + lpR) * 0.5f)
        val fullMono = abs((inL + inR) * 0.5f)
        lfEnv = maxOf(lpMono, lfEnv * windSmooth)
        fullEnv = maxOf(fullMono, fullEnv * windSmooth)
        val dominance = if (windDetection) lfEnv / maxOf(fullEnv, 1e-6f) else 0f
        val windTarget = ((dominance - WIND_OPEN) / (1f - WIND_OPEN)).coerceIn(0f, 1f)
        windAmount = windTarget + windSmooth * (windAmount - windTarget)

        val applied = gain * (1f - duck)
        out[0] = (inL - windAmount * windDepth * lpL) * applied
        out[1] = (inR - windAmount * windDepth * lpR) * applied
        outputMeter.update(out[0], out[1])
    }

    override fun reset() {
        env = 0f
        gain = 1f
        lfEnv = 0f
        fullEnv = 0f
        windAmount = 0f
        handlingRef = 0f
        duck = 0f
        windLpL.reset()
        windLpR.reset()
        inputMeter.reset()
        outputMeter.reset()
    }

    private companion object {
        /** Full reduction is reached this far below the threshold (-12 dB). */
        const val CLOSE_AT = 0.25f
        /** A knock stands this much above the recent peak (9.5 dB). */
        const val KNOCK_RATIO = 3f
        /** Wind counts once the sub-band owns this much of the signal. */
        const val WIND_OPEN = 0.7f
        const val WIND_CUTOFF_HZ = 150f
    }
}

class PhoneMicEmulationModule(private val sampleRate: Float) : RackModule {
    override val kind = PluginKind.PHONE_MIC_EMULATION
    override var enabled = true

    private var targetMic = 0
    private var distanceMeters = 0.3f
    private var proximityAmount = 1f
    private var selfNoiseAmount = 0.5f
    private var dynamicRangeExpansion = 0.5f

    // One filter per channel: a Biquad's z1/z2 is the state of exactly one
    // signal stream. Sharing an instance makes the right channel continue from
    // the left channel's history, which both bleeds L into R and gives a mono
    // source an artificial stereo image.
    private val lowShelfL = Biquad(sampleRate)
    private val lowShelfR = Biquad(sampleRate)
    private val peak2k5L = Biquad(sampleRate)
    private val peak2k5R = Biquad(sampleRate)
    private val highShelfL = Biquad(sampleRate)
    private val highShelfR = Biquad(sampleRate)
    private val highPassL = Biquad(sampleRate)
    private val highPassR = Biquad(sampleRate)

    private val inputMeter = PeakMeter()
    private val outputMeter = PeakMeter()

    // Deterministic self-noise: this suite ships golden parity vectors, so the
    // noise sequence has to be identical in the TypeScript port (and on every
    // render). `Random.nextFloat()` would make the module unrenderable twice.
    private var noiseState = 0x1234_5678

    private fun nextNoise(): Float {
        noiseState = noiseState * 1664525 + 1013904223
        val u = (noiseState ushr 8) and 0xFFFFFF
        return u / 16777216f - 0.5f
    }

    override val inputPeakLevel: Float get() = inputMeter.peak
    override val outputPeakLevel: Float get() = outputMeter.peak

    init {
        configureFilters()
    }

    private fun configureFilters() {
        val (shelfFreq, shelfGain, peakGain, airFreq, airGain, hp) = when (targetMic) {
            0 -> MicCurve(100f, 3f, -2f, 8000f, -3f, 80f)
            1 -> MicCurve(80f, 4f, -3f, 6000f, -4f, 50f)
            else -> MicCurve(100f, 2f, -1f, 10000f, -1f, 80f)
        }
        for (filter in listOf(lowShelfL, lowShelfR)) filter.setLowShelf(shelfFreq, shelfGain, 0.7f)
        for (filter in listOf(peak2k5L, peak2k5R)) filter.setPeaking(2500f, peakGain, 1.5f)
        for (filter in listOf(highShelfL, highShelfR)) filter.setHighShelf(airFreq, airGain, 0.7f)
        for (filter in listOf(highPassL, highPassR)) filter.setHighPass(hp, 0.7071f)
    }

    override fun configure(slot: PluginSlot) {
        enabled = slot.enabled
        targetMic = slot.value("targetMic").toInt().coerceIn(0, 2)
        distanceMeters = slot.value("distanceMeters")
        proximityAmount = slot.value("proximityAmount")
        selfNoiseAmount = slot.value("selfNoiseAmount")
        dynamicRangeExpansion = slot.value("dynamicRangeExpansion")
        configureFilters()
    }

    override fun processSampleInto(inL: Float, inR: Float, out: FloatArray) {
        val inPeak = maxOf(abs(inL), abs(inR))
        inputMeter.update(inPeak, inPeak)

        var l = inL
        var r = inR

        // Step 1: Frequency response correction (independent state per channel)
        l = highPassL.process(l)
        r = highPassR.process(r)
        l = lowShelfL.process(l)
        r = lowShelfR.process(r)
        l = peak2k5L.process(l)
        r = peak2k5R.process(r)
        l = highShelfL.process(l)
        r = highShelfR.process(r)

        // Step 2: Proximity effect
        val proximityBoost = if (distanceMeters > 0f) {
            proximityAmount * (1f / distanceMeters).coerceIn(0f, 6f)
        } else 0f
        val proximityGain = Db.toLinear(proximityBoost)
        l *= proximityGain
        r *= proximityGain

        // Step 3: Self-noise injection (deterministic, see nextNoise)
        val noise = nextNoise() * 0.002f * selfNoiseAmount
        l += noise
        r += noise

        // Step 4: Dynamic range expansion
        val thresholdLinear = Db.toLinear(-30f)
        if (abs(l) < thresholdLinear && dynamicRangeExpansion > 0f) {
            val boost = (thresholdLinear - abs(l)) * dynamicRangeExpansion * 2f
            l *= (1f + boost)
        }
        if (abs(r) < thresholdLinear && dynamicRangeExpansion > 0f) {
            val boost = (thresholdLinear - abs(r)) * dynamicRangeExpansion * 2f
            r *= (1f + boost)
        }

        val outPeak = maxOf(abs(l), abs(r))
        outputMeter.update(outPeak, outPeak)

        out[0] = l
        out[1] = r
    }

    override fun reset() {
        for (filter in listOf(lowShelfL, lowShelfR, peak2k5L, peak2k5R, highShelfL, highShelfR, highPassL, highPassR)) {
            filter.reset()
        }
        noiseState = 0x1234_5678
    }

    private data class MicCurve(
        val shelfFreq: Float,
        val shelfGain: Float,
        val peakGain: Float,
        val airFreq: Float,
        val airGain: Float,
        val highPass: Float,
    )
}
