package com.example

import com.example.audio.ai.MixAdvisor
import com.example.audio.dsp.FourBandEq
import com.example.audio.dsp.PanLaw
import com.example.audio.dsp.PeakLimiter
import com.example.audio.dsp.StereoCompressor
import com.example.audio.engine.OfflineRenderer
import com.example.audio.engine.WavAudioIO
import com.example.audio.history.SessionCommand
import com.example.audio.history.UndoManager
import com.example.audio.model.CompressorConfig
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspAndEngineUnitTest {

    @Test
    fun panLaw_centerIsConstantPower() {
        // -3 dB center attenuation means each channel receives 1/sqrt(2) ~ 0.7071
        val (pL, pR) = PanLaw.calculateGains(0.0f)
        val expected = 1.0f / Math.sqrt(2.0).toFloat()
        assertEquals(expected, pL, 0.001f)
        assertEquals(expected, pR, 0.001f)
        // Energy check: L^2 + R^2 should equal 1.0
        val totalEnergy = pL * pL + pR * pR
        assertEquals(1.0f, totalEnergy, 0.001f)
    }

    @Test
    fun panLaw_hardLeftAndHardRight() {
        val (leftL, leftR) = PanLaw.calculateGains(-1.0f)
        assertEquals(1.0f, leftL, 0.001f)
        assertEquals(0.0f, leftR, 0.001f)

        val (rightL, rightR) = PanLaw.calculateGains(1.0f)
        assertEquals(0.0f, rightL, 0.001f)
        assertEquals(1.0f, rightR, 0.001f)
    }

    @Test
    fun limiter_neverExceedsCeilingOnOverload() {
        val limiter = PeakLimiter(48000f)
        // Send high overload +12 dBFS signal (amplitude = 4.0f)
        var maxObservedPeak = 0.0f
        for (i in 0 until 2000) {
            val (outL, outR) = limiter.processSample(4.0f, 4.0f)
            maxObservedPeak = maxOf(maxObservedPeak, abs(outL), abs(outR))
        }
        // Output must never exceed 1.0f (0 dBFS)
        assertTrue(maxObservedPeak <= 1.0f)
    }

    @Test
    fun compressor_reducesGainOnHotSignal() {
        val comp = StereoCompressor(48000f)
        comp.configure(CompressorConfig(enabled = true, thresholdDb = -18f, ratio = 4f))

        // Feed hot signal (0.8f = -1.9 dBFS)
        for (i in 0 until 1000) {
            comp.processSample(0.8f, 0.8f)
        }
        val gr = comp.currentGainReductionDb
        assertTrue("Gain reduction should be active (> 1.0 dB)", gr > 1.0f)
    }

    @Test
    fun biquadEq_impulseResponseIsStable() {
        val eq = FourBandEq(48000f)
        // Feed Dirac impulse
        val (firstL, firstR) = eq.processSample(1.0f, 1.0f)
        assertFalse(firstL.isNaN() || firstL.isInfinite())
        assertFalse(firstR.isNaN() || firstR.isInfinite())

        var tailIsFinite = true
        for (i in 0 until 1000) {
            val (tailL, tailR) = eq.processSample(0f, 0f)
            if (tailL.isNaN() || tailL.isInfinite() || tailR.isNaN() || tailR.isInfinite()) {
                tailIsFinite = false
                break
            }
        }
        assertTrue("Filter tail must remain finite and decay", tailIsFinite)
    }

    @Test
    fun offlineRenderer_allDspUnitTestsPass() {
        val results = OfflineRenderer.runDspUnitTests()
        assertTrue("Should run at least 4 DSP tests", results.size >= 4)
        for (r in results) {
            assertTrue("DSP Test '${r.testName}' failed: ${r.message}", r.passed)
        }
    }

    @Test
    fun undoManager_restoresExactStateWithoutHoldingAudio() {
        val undoManager = UndoManager(10)
        assertFalse(undoManager.canUndo())

        val initialTracks = listOf(TrackData(index = 0, name = "Kick", colorHex = 0xFFFF0000, gainDb = 0.0f))
        val session = ProjectSession(tracks = initialTracks)

        val updatedTrack = initialTracks[0].copy(gainDb = -6.0f)
        undoManager.pushAction(
            SessionCommand.UpdateTrackParams(
                description = "Fader to -6dB",
                trackIndex = 0,
                beforeTrack = initialTracks[0],
                afterTrack = updatedTrack
            )
        )

        assertTrue(undoManager.canUndo())
        assertEquals("Fader to -6dB", undoManager.getUndoTitle())

        val sessionWithEdit = session.copy(tracks = listOf(updatedTrack))
        val undoResult = undoManager.undo(sessionWithEdit)
        assertTrue(undoResult != null)
        assertEquals(0.0f, undoResult!!.first.tracks[0].gainDb, 0.001f)

        // Can redo
        assertTrue(undoManager.canRedo())
        val redoResult = undoManager.redo(undoResult.first)
        assertTrue(redoResult != null)
        assertEquals(-6.0f, redoResult!!.first.tracks[0].gainDb, 0.001f)
    }

    @Test
    fun aiAdvisor_producesBoundedSuggestions() {
        val tracks = listOf(
            TrackData(index = 0, name = "Vocal", colorHex = 0xFF9C27B0, gainDb = 0f, stemCategory = com.example.audio.model.StemCategory.VOCAL)
        )
        val session = ProjectSession(tracks = tracks)
        val suggestions = MixAdvisor.parseNaturalLanguageCommand("brighten vocal", session)

        assertTrue("Should generate at least 1 suggestion", suggestions.isNotEmpty())
        for (sug in suggestions) {
            assertTrue("Reason must not be empty", sug.reason.isNotBlank())
            assertTrue("Confidence must be between 0 and 1", sug.confidence in 0f..1f)
            assertEquals("Revision must match session", session.revision, sug.targetRevision)
        }
    }

    @Test
    fun dawSettings_presetsAndLatencyCalculations() {
        val settings = com.example.audio.model.DawSettings()
        assertEquals(48000, settings.sampleRateHz)
        assertEquals(com.example.audio.model.BufferSizePreset.BALANCED, settings.bufferSize)
        assertTrue(settings.enableCrashRecovery)

        // Test ultra-low buffer size latency calculation
        val ultraLow = com.example.audio.model.BufferSizePreset.ULTRA_LOW
        assertEquals(128, ultraLow.frames)
        assertTrue("Latency should be under 3ms", ultraLow.latencyMs < 3.0f)

        // Test custom settings copy
        val custom = settings.copy(
            bufferSize = com.example.audio.model.BufferSizePreset.LOW,
            latencyCompensationMs = 24.0f,
            meterBallistics = com.example.audio.model.MeterBallistics.LUFS
        )
        assertEquals(com.example.audio.model.BufferSizePreset.LOW, custom.bufferSize)
        assertEquals(24.0f, custom.latencyCompensationMs, 0.001f)
        assertEquals(com.example.audio.model.MeterBallistics.LUFS, custom.meterBallistics)
    }
}
