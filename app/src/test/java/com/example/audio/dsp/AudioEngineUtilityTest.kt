package com.example.audio.dsp

import com.example.audio.model.AudioClip
import com.example.audio.model.FadeCurve
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class AudioEngineUtilityTest {

    @Test
    fun fadeMath_linearFadeInAndOut() {
        // Fade in: 50% through = 0.5 factor
        val factorIn = FadeMath.calculateFadeFactor(50, 100, FadeCurve.LINEAR, isFadeIn = true)
        assertEquals(0.5f, factorIn, 0.001f)

        // Fade out: 50% through = 0.5 factor
        val factorOut = FadeMath.calculateFadeFactor(50, 100, FadeCurve.LINEAR, isFadeIn = false)
        assertEquals(0.5f, factorOut, 0.001f)
    }

    @Test
    fun fadeMath_equalPowerFadeIn() {
        // Equal power at 50% = sin(PI/4) = 0.707
        val factor = FadeMath.calculateFadeFactor(50, 100, FadeCurve.EQUAL_POWER, isFadeIn = true)
        assertEquals(0.7071f, factor, 0.001f)
    }

    @Test
    fun clipCompositor_simpleComposite() {
        val source = FloatArray(100) { 1.0f } // 50 samples
        val clip = AudioClip(
            name = "Test",
            sourcePath = "test",
            lengthFrames = 10,
            timelineStartFrame = 5,
            trackIndex = 0
        )
        val output = ClipCompositor.compositeClipsFromSources(mapOf(clip.sourcePath to source), listOf(clip), 50L)

        // Output should have samples from 5 to 14
        assertEquals(1.0f, output[10], 0.001f) // Frame 5, L
        assertEquals(1.0f, output[28], 0.001f) // Frame 14, R
        assertEquals(0.0f, output[0], 0.001f)  // Before
        assertEquals(0.0f, output[30], 0.001f) // After
    }
}
