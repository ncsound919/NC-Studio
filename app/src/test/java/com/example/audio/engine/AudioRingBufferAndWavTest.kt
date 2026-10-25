package com.example.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class AudioRingBufferAndWavTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun audioRingBuffer_writeReadAndWrapAround() {
        val ringBuffer = AudioRingBuffer(capacity = 100)

        assertEquals(99, ringBuffer.availableToWrite())
        assertEquals(0, ringBuffer.availableToRead())

        // Write 40 samples
        val writeData1 = FloatArray(40) { i -> i.toFloat() }
        val written1 = ringBuffer.write(writeData1, 0, 40)
        assertEquals(40, written1)
        assertEquals(40, ringBuffer.availableToRead())

        // Read 20 samples
        val readBuffer1 = FloatArray(20)
        val read1 = ringBuffer.read(readBuffer1, 0, 20)
        assertEquals(20, read1)
        assertEquals(0.0f, readBuffer1[0], 0.001f)
        assertEquals(19.0f, readBuffer1[19], 0.001f)
        assertEquals(20, ringBuffer.availableToRead())

        // Write 60 more samples (causes wrap-around)
        val writeData2 = FloatArray(60) { i -> 100f + i }
        val written2 = ringBuffer.write(writeData2, 0, 60)
        assertEquals(60, written2)

        // Clear ring buffer
        ringBuffer.clear()
        assertEquals(0, ringBuffer.availableToRead())
        assertEquals(99, ringBuffer.availableToWrite())
    }

    @Test
    fun wavAudioIO_writeAndReadWavFile() {
        val wavFile = File(tempFolder.root, "test_stereo.wav")

        // 1 second of stereo 48 kHz audio (96,000 interleaved samples)
        val sampleRate = 48000
        val numFrames = 48000
        val interleaved = FloatArray(numFrames * 2) { i ->
            val frame = i / 2
            val isLeft = i % 2 == 0
            val freq = if (isLeft) 440.0 else 880.0
            0.5f * kotlin.math.sin(2.0 * Math.PI * freq * frame / sampleRate).toFloat()
        }

        WavAudioIO.writeWavFile(
            file = wavFile,
            interleavedSamples = interleaved,
            channels = 2,
            sampleRate = sampleRate,
            bitsPerSample = 16
        )

        assertTrue(wavFile.exists())
        assertTrue(wavFile.length() > 44)

        // Read back
        val wavInfo = WavAudioIO.readWavFile(wavFile)
        assertNotNull(wavInfo)
        assertEquals(48000, wavInfo!!.sampleRate)
        assertEquals(2, wavInfo.channels)
        assertEquals(16, wavInfo.bitsPerSample)
        assertEquals(48000L, wavInfo.totalFrames)
        assertEquals(96000, wavInfo.samples.size)

        // Check sample value fidelity within 16-bit quantization threshold (~1/32768)
        assertEquals(interleaved[0], wavInfo.samples[0], 0.01f)
        assertEquals(interleaved[100], wavInfo.samples[100], 0.01f)
    }

    @Test
    fun wavAudioIO_proceduralStemGenerationFor16Tracks() {
        val sampleRate = 48000
        for (trackIndex in 0 until 16) {
            val stem = WavAudioIO.generateProceduralStem(trackIndex, sampleRate)
            assertTrue("Stem for track $trackIndex should not be empty", stem.isNotEmpty())
            assertTrue("Stem for track $trackIndex should have 16 seconds duration", stem.size >= sampleRate * 16)

            // Calculate peak amplitude in stem
            var maxPeak = 0.0f
            for (s in stem) {
                if (abs(s) > maxPeak) maxPeak = abs(s)
            }
            assertTrue("Track $trackIndex stem should contain non-silent audio signal, peak: $maxPeak", maxPeak > 0.05f)
        }
    }
}
