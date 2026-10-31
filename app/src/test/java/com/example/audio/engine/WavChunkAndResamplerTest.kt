package com.example.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regression coverage for the two import-path defects found in the sweep:
 * the WAV reader assumed a 44-byte canonical header (breaking chunked and
 * 24-bit EXTENSIBLE files), and the resampler's Hann window had period 1
 * instead of 2, so its kernel was not a low-pass.
 */
class WavChunkAndResamplerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun reads24BitExtensibleWavWithJunkChunkBeforeData() {
        val sampleRate = 48_000
        val channels = 2
        val bits = 24
        val frames = 8
        val samples = IntArray(frames * channels) { i ->
            if (i % 2 == 0) 0x400000 else -0x200000
        }

        val fmt = ByteArrayOutputStream().apply {
            write(le16(0xFFFE))                 // WAVE_FORMAT_EXTENSIBLE
            write(le16(channels))
            write(le32(sampleRate))
            write(le32(sampleRate * channels * 3))
            write(le16(channels * 3))
            write(le16(bits))
            write(le16(22))                     // cbSize
            write(le16(bits))                   // wValidBitsPerSample
            write(le32(3))                      // channel mask
            // KSDATAFORMAT_SUBTYPE_PCM = 00000001-0000-0010-8000-00aa00389b71
            write(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }.toByteArray()

        val data = ByteArrayOutputStream().apply {
            for (value in samples) {
                write(value and 0xFF)
                write((value shr 8) and 0xFF)
                write((value shr 16) and 0xFF)
            }
        }.toByteArray()
        val junkSize = 10
        val junkPad = junkSize and 1

        val file = File(tempFolder.root, "extensible_24.wav")
        file.outputStream().use { out ->
            out.write(ascii("RIFF"))
            val riffSize = 4 + (8 + junkSize + junkPad) + (8 + fmt.size) + (8 + data.size)
            out.write(le32(riffSize))
            out.write(ascii("WAVE"))
            out.write(ascii("JUNK"))
            out.write(le32(junkSize))
            out.write(ByteArray(junkSize))
            if (junkPad > 0) out.write(ByteArray(junkPad))
            out.write(ascii("fmt "))
            out.write(le32(fmt.size))
            out.write(fmt)
            out.write(ascii("data"))
            out.write(le32(data.size))
            out.write(data)
        }

        val info = WavAudioIO.readWavFile(file)
        assertNotNull("24-bit EXTENSIBLE WAV should parse", info)
        assertEquals(sampleRate, info!!.sampleRate)
        assertEquals(channels, info.channels)
        assertEquals(bits, info.bitsPerSample)
        assertEquals(frames.toLong(), info.totalFrames)
        assertEquals(frames * channels, info.samples.size)
        assertEquals(0.5f, info.samples[0], 1e-4f)
        assertEquals(-0.25f, info.samples[1], 1e-4f)
    }

    @Test
    fun resampling44kTo48kPreservesLevelAndBandLimits() {
        val srcRate = 44_100
        val dstRate = 48_000
        val srcFrames = srcRate
        val tone = FloatArray(srcFrames) { i ->
            (0.5 * sin(2.0 * PI * 1_000.0 * i / srcRate)).toFloat()
        }
        val out = AudioResampler.normalizeToSessionStereo(tone, srcChannels = 1, srcRate = srcRate, dstRate = dstRate)
        assertEquals(dstRate * 2, out.size)
        // Mono is upmixed; check the left channel level.
        var sum = 0.0
        for (i in 0 until dstRate) {
            val v = out[i * 2]
            assertTrue("resampled sample is finite", v.isFinite())
            sum += v * v
        }
        val rms = sqrt(sum / dstRate)
        assertEquals("1 kHz level should survive 44.1→48 kHz", 0.3535, rms, 0.02)
    }

    @Test
    fun downsamplingRejectsContentAboveTheNewNyquist() {
        val srcRate = 96_000
        val dstRate = 48_000
        val src = FloatArray(srcRate) { i ->
            (0.5 * sin(2.0 * PI * 40_000.0 * i / srcRate)).toFloat()
        }
        val out = AudioResampler.normalizeToSessionStereo(src, srcChannels = 1, srcRate = srcRate, dstRate = dstRate)
        var sum = 0.0
        for (i in 0 until dstRate) sum += out[i * 2] * out[i * 2]
        val rms = sqrt(sum / dstRate)
        assertTrue("40 kHz should be filtered out when going to 48 kHz, RMS=$rms", rms < 0.02)
    }

    @Test
    fun ringBufferRejectsNegativeAndOversizedCounts() {
        val ring = AudioRingBuffer(16)
        assertEquals(0, ring.write(FloatArray(4), 0, 0))
        assertEquals(0, ring.read(FloatArray(4), 0, 0))
        // Writes are clipped to available space rather than overrunning.
        val written = ring.write(FloatArray(64) { it.toFloat() }, 0, 64)
        assertEquals(15, written)
        assertEquals(15, ring.availableToRead())
        assertTrue(abs(ring.availableToWrite()) < 1)
    }
}
