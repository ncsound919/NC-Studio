package com.example.audio.engine

import com.example.audio.dsp.ClipCompositor
import com.example.audio.dsp.FadeMath
import com.example.audio.dsp.StereoChorusProcessor
import com.example.audio.model.AudioClip
import com.example.audio.model.ChorusConfig
import com.example.audio.model.FadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClipAdjustAndChorusTest {

    @Test
    fun testFadeMathCurves() {
        // Fade in linear at 50%
        val linHalf = FadeMath.calculateFadeFactor(50L, 100L, FadeCurve.LINEAR, isFadeIn = true)
        assertEquals(0.5f, linHalf, 0.001f)

        // Fade in equal power at 100%
        val eqPowerFull = FadeMath.calculateFadeFactor(100L, 100L, FadeCurve.EQUAL_POWER, isFadeIn = true)
        assertEquals(1.0f, eqPowerFull, 0.001f)

        // Fade out linear at 100%
        val fadeOutLin = FadeMath.calculateFadeFactor(100L, 100L, FadeCurve.LINEAR, isFadeIn = false)
        assertEquals(0.0f, fadeOutLin, 0.001f)
    }

    @Test
    fun testStereoChorusProcessor() {
        val chorus = StereoChorusProcessor(48000f)
        val config = ChorusConfig(
            enabled = true,
            rateHz = 1.5f,
            depthMs = 5.0f,
            feedback = 0.2f,
            stereoSpread = 0.8f,
            mix = 0.5f
        )
        chorus.configure(config)

        val (outL, outR) = chorus.processSample(0.8f, 0.8f)
        assertTrue("Chorus output Left should be non-zero", abs(outL) > 0f)
        assertTrue("Chorus output Right should be non-zero", abs(outR) > 0f)
    }

    @Test
    fun testClipCompositorGainAndFade() {
        val sourceBuffer = FloatArray(4800) { 0.5f } // 100ms mono/stereo source
        val clip = AudioClip(
            name = "Test Clip",
            sourcePath = "test",
            lengthFrames = 1000L,
            timelineStartFrame = 0L,
            gainLinear = 2.0f, // +6 dB gain
            fadeInFrames = 100L,
            fadeOutFrames = 100L,
            trackIndex = 0
        )

        val output = ClipCompositor.compositeClipsFromSources(
            sources = mapOf(clip.sourcePath to sourceBuffer),
            clips = listOf(clip),
            totalTrackFrames = 1000L
        )

        // Middle of clip should have gain = 0.5 * 2.0 = 1.0f
        val midSample = output[500 * 2]
        assertEquals(1.0f, midSample, 0.05f)

        // Start of clip (during fade in) should be < 1.0f
        val startSample = output[10 * 2]
        assertTrue("Fade in sample should be attenuated", startSample < 0.8f)
    }
}
