package com.example.audio.engine

import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

class OfflineRendererStemsTest {

    @Test
    fun stemsIgnoreSessionSoloAndMuteAndPreserveRelativeBalance() {
        val sampleRate = 48000
        val frames = 4800
        val session = ProjectSession(
            projectName = "Stem isolation",
            sampleRate = sampleRate,
            totalLengthFrames = frames.toLong(),
            tracks = listOf(
                TrackData(index = 0, name = "Soloed", colorHex = 0xFFFF5722, isSoloed = true),
                TrackData(index = 1, name = "Muted", colorHex = 0xFFFF9800, isMuted = true)
            )
        )
        val buffers = arrayOf(
            constantBuffer(0.10f, frames),
            constantBuffer(0.40f, frames)
        )
        val dir = Files.createTempDirectory("pulse-stems-test").toFile()

        val files = OfflineRenderer.renderStemsToWav(session, buffers, dir, bitsPerSample = 16)

        assertEquals(2, files.size)
        val soloedStem = WavAudioIO.readWavFile(files[0])!!
        val mutedStem = WavAudioIO.readWavFile(files[1])!!
        val soloedPeak = peakOf(soloedStem.samples)
        val mutedPeak = peakOf(mutedStem.samples)

        // The muted target track must still be exported, and the solo on track 0
        // must not bleed into its stem.
        assertTrue("target stem should contain audio", mutedPeak > 0.1f)
        assertTrue("soloed track must not leak into target stem", soloedPeak < 0.1f)
        assertEquals("stems must keep their relative balance", 4.0f, mutedPeak / soloedPeak, 0.15f)

        files.forEach { it.delete() }
        dir.delete()
    }

    private fun constantBuffer(value: Float, frames: Int) = FloatArray(frames * 2) { value }

    /** Peak over the first 1000 frames, avoiding the 2 s minimum render tail. */
    private fun peakOf(samples: FloatArray): Float {
        val frames = minOf(1000, samples.size / 2)
        var peak = 0f
        for (frame in 0 until frames) {
            peak = maxOf(peak, abs(samples[frame * 2]))
        }
        return peak
    }
}
