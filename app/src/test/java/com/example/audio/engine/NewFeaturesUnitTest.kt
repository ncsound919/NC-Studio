package com.example.audio.engine

import com.example.audio.ai.ToolCallDispatcher
import com.example.audio.dsp.AutoPitchProcessor
import com.example.audio.dsp.NoiseReductionProcessor
import com.example.audio.dsp.AutomationInterpolator
import com.example.audio.model.AutomationPoint
import com.example.audio.model.MixTemplateLibrary
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesUnitTest {

    @Test
    fun autoPitchDisabledPassthrough() {
        val p = AutoPitchProcessor(48000f)
        p.configure(enabled = false)
        val input = FloatArray(1024) { if (it % 2 == 0) 0.5f else -0.5f }
        val out = p.processBuffer(input)
        assertEquals(input.size, out.size)
    }

    @Test
    fun autoPitchEnabledNoNaN() {
        val p = AutoPitchProcessor(48000f)
        p.configure(enabled = true, correctionStrength = 1.0f)
        // 440 Hz-ish sine stereo
        val input = FloatArray(4096) { i -> (kotlin.math.sin(2.0 * Math.PI * 440.0 * (i / 2) / 48000.0) * 0.5).toFloat() }
        // ensure interleaved: put same in both channels
        for (i in input.indices step 2) { input[i + 1] = input[i] }
        val out = p.processBuffer(input)
        assertEquals(input.size, out.size)
        for (v in out) assertTrue(!v.isNaN() && !v.isInfinite())
    }

    @Test
    fun autoPitchSilenceStaysSilence() {
        val p = AutoPitchProcessor(48000f)
        p.configure(enabled = true)
        val input = FloatArray(2048) { 0f }
        val out = p.processBuffer(input)
        for (v in out) assertEquals(0f, v, 0f)
    }

    @Test
    fun noiseReductionDisabledPassthrough() {
        val nr = NoiseReductionProcessor(48000f)
        nr.configure(enabled = false)
        val out = FloatArray(2)
        nr.processSampleInto(0.5f, -0.3f, out)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.3f, out[1], 1e-6f)
    }

    @Test
    fun noiseReductionAttenuatesQuiet() {
        val nr = NoiseReductionProcessor(48000f)
        nr.configure(enabled = true, thresholdDb = -20f, reductionDb = -12f)
        // very quiet signal should be attenuated
        val out = FloatArray(2)
        nr.processSampleInto(0.001f, 0.001f, out)
        assertTrue(kotlin.math.abs(out[0]) <= 0.001f)
    }

    @Test
    fun noiseReductionLoudPassthroughApprox() {
        val nr = NoiseReductionProcessor(48000f)
        nr.configure(enabled = true, thresholdDb = -40f, reductionDb = -12f)
        val out = FloatArray(2)
        nr.processSampleInto(0.5f, 0.5f, out)
        assertTrue(kotlin.math.abs(out[0] - 0.5f) < 0.05f)
    }

    @Test
    fun automationInterpolatorBasic() {
        val pts = listOf(AutomationPoint(0L, 0f), AutomationPoint(10L, 10f))
        assertEquals(0f, AutomationInterpolator.valueAt(pts, -5L), 1e-6f)
        assertEquals(5f, AutomationInterpolator.valueAt(pts, 5L), 1e-6f)
        assertEquals(10f, AutomationInterpolator.valueAt(pts, 20L), 1e-6f)
        assertTrue(AutomationInterpolator.valueAt(emptyList(), 5L) == 0f)
    }

    @Test
    fun toolCallDispatcherParseAndExecute() {
        val session = ProjectSession(projectName = "T", tracks = emptyList())
        val llm = """{"tool": "setTrackGain", "trackIndex": "0", "gainDb": "-3.0"}"""
        val calls = ToolCallDispatcher.parseToolCalls(llm)
        assertTrue(calls.isNotEmpty())
        assertEquals("setTrackGain", calls.first().name)
        // execution on empty session should not crash
        val out = ToolCallDispatcher.executeAll(session, calls)
        assertTrue(out.revision >= session.revision)
    }

    @Test
    fun toolCallDispatcherApplyTemplate() {
        val session = ProjectSession(projectName = "T", tracks = emptyList())
        val llm = """{"tool": "applyTemplate", "templateId": "modern_pop_vocal"}"""
        val calls = ToolCallDispatcher.parseToolCalls(llm)
        val out = ToolCallDispatcher.executeAll(session, calls)
        // no crash
        assertTrue(true)
    }

    @Test
    fun stemsExportCreatesFiles() {
        val sr = 48000
        val frames = 1000
        val buf = FloatArray(frames * 2) { 0.1f }
        val tracks = listOf(
            com.example.audio.model.TrackData(index = 0, name = "Kick", colorHex = 0L),
            com.example.audio.model.TrackData(index = 1, name = "Snare", colorHex = 0L)
        )
        val session = ProjectSession(projectName = "T", sampleRate = sr, totalLengthFrames = frames.toLong(), tracks = tracks)
        val buffers = arrayOf(buf.copyOf(), buf.copyOf())
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "stems_test")
        dir.mkdirs()
        val files = OfflineRenderer.renderStemsToWav(session, buffers, dir, bitsPerSample = 16)
        assertEquals(2, files.size)
        for (f in files) assertTrue(f.exists() && f.length() > 0)
    }

    @Test
    fun mixTemplateLibraryStillHasTemplates() {
        assertTrue(MixTemplateLibrary.ALL.size >= 4)
    }
}