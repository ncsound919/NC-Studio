package com.example.audio.engine

import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExportFallbackTest {

    @Test
    fun unavailableEncoderWritesARealWavInsteadOfMislabeledMp3() {
        val session = ProjectSession(
            projectName = "Fallback",
            sampleRate = 48000,
            totalLengthFrames = 48000L,
            tracks = listOf(TrackData(index = 0, name = "Tone", colorHex = 0xFFFF5722))
        )
        val dir = Files.createTempDirectory("pulse-export-test").toFile()
        val requested = File(dir, "master.mp3")
        val buffers = arrayOf(FloatArray(48000 * 2) { 0.1f })

        val actual = OfflineRenderer.renderSessionToMp3(session, buffers, requested)

        assertNotNull("fallback must produce a playable file", actual)
        assertEquals("wav", actual!!.extension)
        assertFalse("the empty .mp3 target must not remain", requested.exists())
        val wav = WavAudioIO.readWavFile(actual)
        assertNotNull("fallback file must be a valid WAV", wav)
        assertTrue(actual.length() > 44)

        actual.delete()
        dir.delete()
    }
}
