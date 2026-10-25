package com.example.audio.engine

import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineRendererAndTelemetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun offlineRenderer_renders16TrackSessionToWavFile() {
        val sampleRate = 48000
        val sessionFrames = sampleRate * 2L // 2 second mixdown test

        val tracks = (0 until 16).map { i ->
            TrackData(index = i, name = "Track $i", colorHex = 0xFF00E5FF, gainDb = -3.0f)
        }
        val session = ProjectSession(
            totalLengthFrames = sessionFrames,
            tracks = tracks
        )

        // Synthesize short audio buffers for 16 tracks
        val trackBuffers = Array(16) { i ->
            FloatArray(sessionFrames.toInt()) { frame ->
                0.2f * kotlin.math.sin(2.0 * Math.PI * (220.0 + i * 50) * frame / sampleRate).toFloat()
            }
        }

        val outputFile = File(tempFolder.root, "render_test.wav")
        var reportedProgress = 0f

        OfflineRenderer.renderSessionToWav(
            project = session,
            trackBuffers = trackBuffers,
            outputFile = outputFile,
            onProgress = { progress ->
                reportedProgress = progress
            }
        )

        assertTrue(outputFile.exists())
        assertTrue("Rendered WAV file should have valid data size", outputFile.length() > 44)
        assertEquals(1.0f, reportedProgress, 0.01f)

        // Read rendered file and verify header
        val readWav = WavAudioIO.readWavFile(outputFile)
        assertNotNull(readWav)
        assertEquals(48000, readWav!!.sampleRate)
        assertEquals(2, readWav.channels)
        assertEquals(sessionFrames, readWav.totalFrames)
    }

    @Test
    fun offlineRenderer_dspRegressionTestsPass() {
        val testResults = OfflineRenderer.runDspUnitTests()

        assertTrue("DSP test suite should contain tests", testResults.isNotEmpty())
        for (res in testResults) {
            assertTrue("DSP test '${res.testName}' must pass", res.passed)
        }
    }
}
