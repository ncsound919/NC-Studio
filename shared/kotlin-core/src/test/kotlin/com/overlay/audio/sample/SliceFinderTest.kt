package com.overlay.audio.sample

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SliceFinderTest {

    private val rate = 48_000

    private fun tone(ms: Int, amp: Float = 0.8f, hz: Double = 220.0): FloatArray =
        FloatArray(rate * ms / 1000) { i -> (amp * sin(2.0 * Math.PI * hz * i / rate)).toFloat() }

    private fun noise(ms: Int, amp: Float = 0.5f): FloatArray {
        // Deterministic pseudo-noise: no Random, so the test cannot flake.
        var seed = 12345
        return FloatArray(rate * ms / 1000) {
            seed = seed * 1103515245 + 12345
            ((seed ushr 16) and 0x7FFF) / 32767f * 2f * amp - amp
        }
    }

    private fun silence(ms: Int) = FloatArray(rate * ms / 1000)

    private fun threeHits(): FloatArray {
        val out = ArrayList<Float>()
        out.addAll(silence(80).toList())
        repeat(3) {
            out.addAll(tone(80).toList())
            out.addAll(silence(120).toList())
        }
        return out.toFloatArray()
    }

    @Test
    fun silenceHasNoCuts() {
        assertEquals(emptyList(), SliceFinder.findCuts(silence(500), rate))
    }

    @Test
    fun eachHitAfterSilenceGetsACut() {
        val samples = threeHits()
        val cuts = SliceFinder.findCuts(samples, rate)

        // Hits start at 80, 280 and 480 ms. The first is not a cut: nothing
        // precedes it, so slicing there would only produce a leading silent pad.
        assertEquals(2, cuts.size, "cuts=$cuts")
        assertNear(280, cuts[0])
        assertNear(480, cuts[1])
    }

    @Test
    fun cutsLandOnASignChange() {
        val samples = threeHits()
        for (cut in SliceFinder.findCuts(samples, rate)) {
            val before = samples[cut - 1]
            val at = samples[cut]
            assertTrue((before < 0f) != (at < 0f), "cut $cut is not a zero crossing")
        }
    }

    @Test
    fun nearestZeroCrossingIsPreferred() {
        val samples = FloatArray(100) { if (it % 10 < 5) 0.2f else -0.2f }
        assertEquals(35, SliceFinder.zeroCrossing(samples, 33, 5))
        // Nothing to snap to inside an all-positive stretch.
        val positive = FloatArray(100) { 0.5f }
        assertEquals(33, SliceFinder.zeroCrossing(positive, 33, 5))
    }

    @Test
    fun aSharpRiseInsideContinuousMaterialStillCuts() {
        // 100 ms quiet-but-audible, then a jump: the gate never reopens, so only
        // the rise test can find this onset.
        val samples = tone(100, 0.2f) + tone(200, 0.9f)
        val cuts = SliceFinder.findCuts(samples, rate)

        assertEquals(1, cuts.size, "cuts=$cuts")
        assertNear(100, cuts[0])
    }

    @Test
    fun minimumSliceLengthCollapsesCloseOnsets() {
        val samples = silence(100) + tone(40) + silence(10) + tone(40) + silence(100)
        val tight = SliceFinder.findCuts(samples, rate, SliceFinder.Settings(minSliceMs = 200))
        val loose = SliceFinder.findCuts(samples, rate, SliceFinder.Settings(minSliceMs = 30))

        assertEquals(0, tight.size, "200 ms slices cannot separate 50 ms of audio: $tight")
        assertEquals(1, loose.size, "30 ms slices separate the two hits: $loose")
    }

    @Test
    fun cutsAreCapped() {
        val out = ArrayList<Float>()
        repeat(10) {
            out.addAll(tone(40).toList())
            out.addAll(silence(60).toList())
        }
        val cuts = SliceFinder.findCuts(
            out.toFloatArray(), rate, SliceFinder.Settings(minSliceMs = 30, maxSlices = 3),
        )
        assertEquals(3, cuts.size)
    }

    @Test
    fun settingsAreClampedInsteadOfCrashing() {
        val cuts = SliceFinder.findCuts(
            threeHits(), rate,
            SliceFinder.Settings(gateFraction = 0f, hopMs = 0, minSliceMs = 0, maxSlices = 0),
        )
        assertTrue(cuts.size <= 1, "maxSlices=0 coerces to 1: $cuts")
    }

    @Test
    fun profileOfSilenceIsAllZero() {
        val profile = SliceFinder.profile(silence(250), rate)
        assertEquals(0f, profile.peak)
        assertEquals(0f, profile.rms)
        assertEquals(0f, profile.attackMs)
        assertEquals(250f, profile.durationMs)
    }

    @Test
    fun profileMeasuresAShortHit() {
        val samples = silence(50) + tone(60) + silence(50)
        val profile = SliceFinder.profile(samples, rate)

        assertTrue(profile.peak > 0.7f, "peak=${profile.peak}")
        // A sine bursts instantly: the attack is measured from the -20 dB point.
        assertTrue(profile.attackMs < 30f, "attack=${profile.attackMs}")
        // Decay runs from the peak until the signal falls 20 dB below it.
        assertTrue(profile.decayMs > 0f, "decay=${profile.decayMs}")
        assertTrue(profile.crest > 1f, "crest=${profile.crest}")
    }

    @Test
    fun noiseReadsBrighterThanATone() {
        val toneProfile = SliceFinder.profile(tone(200, hz = 100.0), rate)
        val noiseProfile = SliceFinder.profile(noise(200), rate)
        assertTrue(
            noiseProfile.brilliance > toneProfile.brilliance * 4f,
            "noise=${noiseProfile.brilliance} tone=${toneProfile.brilliance}",
        )
    }

    @Test
    fun aCutIsNotPlacedWhereItWouldLeaveAStubSlice() {
        // Hits at 0, 200 and 360 ms of a 400 ms file. The last onset leaves only
        // 40 ms behind it, short of the 60 ms minimum slice, so it must not
        // become a pad of a few milliseconds.
        val samples = tone(60) + silence(140) + tone(60) + silence(100) + tone(40)
        val cuts = SliceFinder.findCuts(samples, rate)

        assertEquals(1, cuts.size, "only the 200 ms onset leaves room: $cuts")
        assertNear(200, cuts[0])
    }

    /** [expectedMs] is where the onset sits; the cut is a frame index. */
    private fun assertNear(expectedMs: Int, cut: Int) {
        val ms = cut * 1000.0 / rate
        val toleranceMs = 12.0
        assertTrue(
            abs(ms - expectedMs) <= toleranceMs,
            "cut $cut is ${"%.1f".format(ms)} ms, expected ${expectedMs}ms ±${toleranceMs}ms",
        )
    }
}
