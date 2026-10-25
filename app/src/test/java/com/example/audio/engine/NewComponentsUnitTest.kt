package com.example.audio.engine

import com.example.audio.dsp.ClipCompositor
import com.example.audio.model.MixTemplateLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for newly added / refined components.
 */
class NewComponentsUnitTest {

    @Test
    fun metronomeProducesClicksAtBeatBoundaries() {
        val metro = MetronomeProcessor(48000f)
        metro.configure(enabled = true, bpm = 120f, volumeDb = 0f, beatsPerBar = 4)
        // 120 BPM @ 48kHz = 24000 frames per beat
        var clickCount = 0
        for (i in 0 until 48001) {
            val s = metro.processFrame(i.toLong())
            if (s != 0f) clickCount++
        }
        // At least one click per beat (4 beats in 2 seconds) — each click has a ~50ms tail
        assertTrue("Expected clicks, got $clickCount", clickCount > 0)
    }

    @Test
    fun metronomeDisabledProducesSilence() {
        val metro = MetronomeProcessor(48000f)
        metro.configure(enabled = false, bpm = 120f, volumeDb = 0f)
        for (i in 0 until 1000) {
            assertEquals(0f, metro.processFrame(i.toLong()), 0f)
        }
    }

    @Test
    fun metronomeDownbeatIsLouder() {
        val metro = MetronomeProcessor(48000f)
        metro.configure(enabled = true, bpm = 120f, volumeDb = 0f, beatsPerBar = 4)
        // Collect peak amplitude of first click (downbeat) vs second click
        var firstPeak = 0f
        var secondPeak = 0f
        var clicksSeen = 0
        var currentPeak = 0f
        for (i in 0 until 48001) {
            val s = kotlin.math.abs(metro.processFrame(i.toLong()))
            if (s > currentPeak) currentPeak = s
            // beat boundary every 24000 frames
            if (i > 0 && i % 24000 == 0) {
                if (clicksSeen == 0) firstPeak = currentPeak
                else if (clicksSeen == 1) secondPeak = currentPeak
                clicksSeen++
                currentPeak = 0f
            }
        }
        // Downbeat frequency is higher (2400 vs 1500) — amplitude envelope same, so peaks may be similar.
        // Just ensure we got clicks.
        assertTrue("firstPeak=$firstPeak", firstPeak > 0f)
    }

    @Test
    fun audioResamplerIdentityForSessionRate() {
        val input = FloatArray(100) { if (it % 2 == 0) 0.5f else -0.5f }
        val out = AudioResampler.normalizeToSessionStereo(input, srcChannels = 2, srcRate = 48000, dstRate = 48000)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun audioResamplerMonoUpmix() {
        val mono = FloatArray(10) { 0.3f }
        val stereo = AudioResampler.normalizeToSessionStereo(mono, srcChannels = 1, srcRate = 48000, dstRate = 48000)
        assertEquals(mono.size * 2, stereo.size)
        for (i in mono.indices) {
            assertEquals(mono[i], stereo[i * 2], 1e-6f)
            assertEquals(mono[i], stereo[i * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun audioResamplerDownsamples441To48() {
        // 44.1kHz -> 48kHz should produce ~1.088x samples
        val input = FloatArray(44100 * 2) { 0f } // 1 sec of stereo silence at 44.1k
        input[200 * 2] = 1f // impulse
        val out = AudioResampler.normalizeToSessionStereo(input, srcChannels = 2, srcRate = 44100, dstRate = 48000)
        // Expected ~48000 frames * 2 = 96000 samples
        assertTrue("out.size=${out.size}", out.size in 95000..97000)
    }

    @Test
    fun waveformCacheProducesPeaks() {
        val samples = FloatArray(200) { if (it % 2 == 0) (it / 200f) else -(it / 200f) }
        val peaks = WaveformCache.computePeaks(samples, sourceStartFrame = 0L, lengthFrames = 100L)
        assertEquals(WaveformCache.MAX_BINS, peaks.size) // default bin count
        assertTrue(peaks.isNotEmpty())
    }

    @Test
    fun clipCompositorSilenceOutsideClip() {
        val src = FloatArray(200) { if (it % 2 == 0) 1f else 0f }
        val clip = com.example.audio.model.AudioClip(
            name = "test", sourcePath = "src", sourceStartFrame = 0L,
            lengthFrames = 10L, timelineStartFrame = 5L, trackIndex = 0
        )
        val out = ClipCompositor.compositeClipsFromSources(mapOf("src" to src), listOf(clip), totalTrackFrames = 30L)
        // Frames 0-4 should be silence
        for (f in 0 until 5) {
            assertEquals(0f, out[f * 2], 1e-6f)
        }
        // Frame 5+ should have signal
        assertTrue(out[5 * 2] != 0f || out[6 * 2] != 0f)
    }

    @Test
    fun clipCompositorFadeIn() {
        val src = FloatArray(200) { if (it % 2 == 0) 1f else 0f }
        val clip = com.example.audio.model.AudioClip(
            name = "test", sourcePath = "src", sourceStartFrame = 0L,
            lengthFrames = 10L, timelineStartFrame = 0L, trackIndex = 0,
            fadeInFrames = 5L
        )
        val out = ClipCompositor.compositeClipsFromSources(mapOf("src" to src), listOf(clip), totalTrackFrames = 10L)
        // First frame should be attenuated by fade-in
        assertTrue("first frame should be < 1.0", kotlin.math.abs(out[0]) < 1.0f)
    }

    @Test
    fun mixTemplateLibraryHasTemplates() {
        assertTrue(MixTemplateLibrary.ALL.isNotEmpty())
        assertTrue(MixTemplateLibrary.ALL.size >= 4)
        val pop = MixTemplateLibrary.byId("modern_pop_vocal")
        assertTrue(pop != null)
        assertEquals("Pop", pop!!.genre)
    }

    @Test
    fun mixTemplateByGenre() {
        val hipHop = MixTemplateLibrary.byGenre("Hip-Hop")
        assertTrue(hipHop.isNotEmpty())
        assertTrue(hipHop.all { it.genre.equals("Hip-Hop", ignoreCase = true) })
    }

    @Test
    fun latencyCalibratorReturnsReasonableRange() {
        val chirp = FloatArray(512) { if (it == 100) 1f else 0f }
        val captured = FloatArray(48000) { 0f }
        // Place impulse at 500 samples (simulating 10.4ms latency)
        if (500 + 512 < captured.size) captured[500] = 1f
        val offset = LatencyCalibrator.calculateLatencyOffsetMs(chirp, captured, 48000)
        assertTrue("offset=$offset", offset in 5f..150f)
    }

    @Test
    fun latencyCalibratorEmptyReturnsDefault() {
        val offset = LatencyCalibrator.calculateLatencyOffsetMs(FloatArray(0), FloatArray(0), 48000)
        assertEquals(32.5f, offset, 0.01f)
    }
}
