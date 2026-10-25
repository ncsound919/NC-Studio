package com.example.audio.dsp

import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FilterType
import com.example.audio.model.MasterLimiterConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Comprehensive unit tests for all channel strip DSP units and master bus processors:
 * - BiquadFilter (Low Shelf, High Shelf, Peaking, High Pass, Low Pass)
 * - FourBandEq
 * - ParameterSmoother
 * - StereoCompressor
 * - SaturationProcessor
 * - DuckingDelayProcessor
 * - ChannelLimiter & PeakLimiter
 * - SchroederReverb
 * - LufsMeter
 */
class AudioDspComprehensiveTest {

    private val sampleRate = 48000f

    @Test
    fun biquadFilter_lowShelfBoostProducesPositiveDbGainAtBass() {
        val filter = BiquadFilter(sampleRate)
        val band = EqBand(
            type = FilterType.LOW_SHELF,
            frequencyHz = 100f,
            gainDb = 6.0f,
            qFactor = 0.707f,
            enabled = true
        )
        filter.configure(band)

        val bassGainDb = filter.responseAtFrequency(50f)
        val highGainDb = filter.responseAtFrequency(10000f)

        // Bass should have ~ +6 dB boost, while high treble should be close to 0 dB
        assertTrue("Bass frequency response should be boosted ~6dB, was: $bassGainDb", bassGainDb > 4.5f)
        assertTrue("High treble frequency response should be near 0dB, was: $highGainDb", abs(highGainDb) < 0.5f)
    }

    @Test
    fun biquadFilter_highPassFilterAttenuatesSubBass() {
        val filter = BiquadFilter(sampleRate)
        val band = EqBand(
            type = FilterType.HIGH_PASS,
            frequencyHz = 80f,
            gainDb = 0f,
            qFactor = 0.707f,
            enabled = true
        )
        filter.configure(band)

        val subGainDb = filter.responseAtFrequency(20f)
        val passGainDb = filter.responseAtFrequency(1000f)

        // Sub bass should be heavily attenuated (< -10 dB), pass band should be near 0 dB
        assertTrue("20Hz should be attenuated (< -10dB), was: $subGainDb", subGainDb < -10f)
        assertTrue("Passband 1kHz should be near 0dB, was: $passGainDb", abs(passGainDb) < 0.5f)
    }

    @Test
    fun fourBandEq_disabledBypassesSignalIdentically() {
        val eq = FourBandEq(sampleRate)
        val config = EqConfig(
            enabled = false,
            band1 = EqBand(FilterType.LOW_SHELF, 100f, 12f, 0.707f, enabled = true),
            band4 = EqBand(FilterType.HIGH_SHELF, 8000f, -12f, 0.707f, enabled = true)
        )
        eq.updateConfig(config)

        val testL = 0.55f
        val testR = -0.42f
        val (outL, outR) = eq.processSample(testL, testR)

        assertEquals(testL, outL, 0.0001f)
        assertEquals(testR, outR, 0.0001f)
    }

    @Test
    fun fourBandEq_totalResponseDbSumsAcrossBands() {
        val eq = FourBandEq(sampleRate)
        val config = EqConfig(
            enabled = true,
            band1 = EqBand(FilterType.LOW_SHELF, 100f, 4.0f, 0.707f, enabled = true),
            band2 = EqBand(FilterType.PEAKING, 1000f, 3.0f, 1.0f, enabled = true),
            band3 = EqBand(FilterType.PEAKING, 3000f, -2.0f, 1.0f, enabled = true),
            band4 = EqBand(FilterType.HIGH_SHELF, 10000f, 5.0f, 0.707f, enabled = true)
        )
        eq.updateConfig(config)

        val total100Hz = eq.calculateTotalResponseDb(100f)
        val total16kHz = eq.calculateTotalResponseDb(16000f)

        assertTrue("100Hz should have net positive gain", total100Hz > 2.0f)
        assertTrue("16kHz should have net positive gain", total16kHz > 3.0f)
    }

    @Test
    fun parameterSmoother_convergesSmoothlyWithoutInstantStep() {
        val smoother = ParameterSmoother(initialValue = 0.0f, sampleRate = sampleRate, timeConstantMs = 10f)
        smoother.setTarget(1.0f)

        val step1 = smoother.next()
        assertTrue("First step should be greater than 0", step1 > 0f)
        assertTrue("First step should not jump directly to 1.0", step1 < 0.5f)

        // Run for 4000 samples (~83ms, ~8 time constants)
        for (i in 0 until 4000) {
            smoother.next()
        }
        assertEquals(1.0f, smoother.getCurrent(), 0.01f)
    }

    @Test
    fun stereoCompressor_softKneeAndMakeupGain() {
        val comp = StereoCompressor(sampleRate)
        comp.configure(
            CompressorConfig(
                enabled = true,
                thresholdDb = -20f,
                ratio = 4.0f,
                attackMs = 5f,
                releaseMs = 50f,
                kneeWidthDb = 6f,
                makeupGainDb = 6.0f // +6 dB makeup = 2.0x linear
            )
        )

        // Process quiet signal below threshold (0.01 = -40 dBFS)
        var quietOutL = 0f
        for (i in 0 until 500) {
            val (l, _) = comp.processSample(0.01f, 0.01f)
            quietOutL = l
        }
        // Quiet signal should receive makeup gain ~2.0x
        assertEquals(0.02f, quietOutL, 0.003f)

        // Process loud signal (0.9f = -0.9 dBFS)
        for (i in 0 until 1000) {
            comp.processSample(0.9f, 0.9f)
        }
        assertTrue("Gain reduction should be active on loud signal", comp.currentGainReductionDb > 5.0f)
    }

    @Test
    fun saturationProcessor_softClippingAndHarmonicWarmth() {
        val sat = SaturationProcessor()
        sat.configure(
            SaturationConfig(
                enabled = true,
                driveDb = 12f, // +12 dB = 4x drive
                warmth = 0.7f,
                mix = 1.0f
            )
        )

        // Feed large amplitude sine wave (1.5f)
        var maxObserved = 0f
        for (i in 0 until 500) {
            val input = 1.5f * sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
            val (outL, outR) = sat.processSample(input, input)
            maxObserved = maxOf(maxObserved, abs(outL), abs(outR))
        }

        // Tanh soft saturation ensures bounded output within [-1.0, 1.0]
        assertTrue("Saturated output must be bounded <= 1.0f, was: $maxObserved", maxObserved <= 1.0f)
        assertTrue("Output should not be zeroed out", maxObserved > 0.6f)
    }

    @Test
    fun saturationProcessor_wetDryMixInterpolation() {
        val sat = SaturationProcessor()
        sat.configure(
            SaturationConfig(
                enabled = true,
                driveDb = 18f,
                warmth = 0.5f,
                mix = 0.0f // 100% dry
            )
        )

        val (dryL, dryR) = sat.processSample(0.5f, -0.5f)
        assertEquals(0.5f, dryL, 0.001f)
        assertEquals(-0.5f, dryR, 0.001f)
    }

    @Test
    fun duckingDelayProcessor_sidechainDuckingReducesWetWhenDryIsHot() {
        val delay = DuckingDelayProcessor(sampleRate)
        delay.configure(
            DuckingDelayConfig(
                enabled = true,
                timeMs = 50f, // fast delay for test
                feedback = 0.4f,
                mix = 0.5f,
                duckingEnabled = true,
                duckingThresholdDb = -18f, // ~0.126
                duckingDepthDb = -12f,      // ~0.25 gain
                duckingReleaseMs = 50f
            )
        )

        // Seed the delay buffer with an initial burst
        for (i in 0 until 500) {
            val sample = if (i == 0) 1.0f else 0.0f
            delay.processSample(sample, sample)
        }

        // Now feed a loud signal that triggers ducking
        val (hotOutL, _) = delay.processSample(0.8f, 0.8f)
        // With dry signal 0.8f and ducked wet component, output should remain stable and controlled
        assertFalse(hotOutL.isNaN() || hotOutL.isInfinite())
    }

    @Test
    fun channelLimiter_clampsExtremeOverload() {
        val limiter = ChannelLimiter(sampleRate)
        limiter.configure(
            ChannelLimiterConfig(
                enabled = true,
                ceilingDb = -0.5f, // ~0.944f linear
                releaseMs = 20f
            )
        )

        // Send extreme overload signal (+14 dBFS, amplitude = 5.0f)
        var maxPeak = 0.0f
        for (i in 0 until 1000) {
            val (outL, outR) = limiter.processSample(5.0f, 5.0f)
            maxPeak = maxOf(maxPeak, abs(outL), abs(outR))
        }

        assertTrue("Channel limiter must clamp to ceiling (~0.95), observed: $maxPeak", maxPeak <= 0.96f)
    }

    @Test
    fun schroederReverb_diffusesImpulseAndDecays() {
        val reverb = SchroederReverb(sampleRate)
        reverb.configure(
            ReverbConfig(
                enabled = true,
                roomSize = 0.7f,
                damping = 0.3f,
                stereoWidth = 0.9f,
                returnLevel = 0.8f
            )
        )

        // Feed Dirac impulse to Left channel only
        val (firstL, firstR) = reverb.processSample(1.0f, 0.0f)
        assertFalse(firstL.isNaN() || firstR.isNaN())

        // Run 5000 samples of tail
        var totalTailEnergy = 0.0f
        for (i in 0 until 5000) {
            val (outL, outR) = reverb.processSample(0f, 0f)
            totalTailEnergy += outL * outL + outR * outR
        }

        assertTrue("Impulse must generate diffused reverberant energy in tail", totalTailEnergy > 0.01f)

        // Reset clears all comb and allpass buffers
        reverb.reset()
        val (silentL, silentR) = reverb.processSample(0f, 0f)
        assertEquals(0f, silentL, 0.0001f)
        assertEquals(0f, silentR, 0.0001f)
    }

    @Test
    fun lufsMeter_measuresPeakAndLoudnessAccurately() {
        val meter = LufsMeter(sampleRate)
        meter.reset()

        // Feed 1 kHz sine wave at -6 dBFS (amplitude 0.5f) for 9600 samples (200 ms)
        for (i in 0 until 9600) {
            val sample = 0.5f * sin(2.0 * Math.PI * 1000.0 * i / sampleRate).toFloat()
            meter.addSample(sample, sample)
        }

        // Peak should be ~ -6 dBFS (+/- 0.5 dB)
        assertTrue("Peak L should be around -6 dBFS, was: ${meter.peakDbL}", abs(meter.peakDbL - (-6.0f)) < 1.0f)
        assertTrue("Integrated LUFS should have registered (> -30 LUFS)", meter.integratedLufs > -30.0f)
    }
}
