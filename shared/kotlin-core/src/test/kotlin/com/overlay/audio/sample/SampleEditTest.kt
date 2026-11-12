package com.overlay.audio.sample

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared sampling contract is executable: the same JSON cases in
 * `shared/contracts/sample-edit.v1.json` run here and in
 * `new dsp/src/lib/sampleEdit.test.ts`. A drift in either implementation fails
 * one side of the parity pair.
 */
class SampleEditTest {

    private fun contractFile(): File {
        val candidates = listOf(
            "../contracts/sample-edit.v1.json",
            "contracts/sample-edit.v1.json",
            "shared/contracts/sample-edit.v1.json",
            "../../shared/contracts/sample-edit.v1.json",
        )
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: error("sample-edit.v1.json not found; run from the repo or shared/kotlin-core")
    }

    private fun contract(): JSONObject = JSONObject(contractFile().readText())

    private fun signalFrom(spec: JSONObject, sampleRate: Int): FloatArray {
        val amplitude = spec.optDouble("amplitude", 0.5).toFloat()
        val lead = spec.optInt("leadSilenceMs", 0)
        val firstTone = spec.optJSONArray("toneMs")?.optInt(0, 0) ?: 0
        val gap = spec.optJSONArray("gapMs")?.optInt(0, 0) ?: 0
        val secondTone = spec.optJSONArray("toneMs2")?.optInt(0, 0) ?: 0
        val ms = lead + firstTone + gap + secondTone
        val frames = ms * sampleRate / 1000
        val out = FloatArray(frames)
        var from = lead * sampleRate / 1000
        var to = from + firstTone * sampleRate / 1000
        for (i in from until to) out[i] = amplitude
        from = to + gap * sampleRate / 1000
        to = from + secondTone * sampleRate / 1000
        for (i in from until to) out[i] = amplitude
        return out
    }

    @Test
    fun `parity cases match the shared contract`() {
        val doc = contract()
        val cases = doc.getJSONObject("cases")
        val sampleRate = cases.getInt("sampleRate")
        val signalRate = sampleRate
        val casesArray = cases.getJSONArray("cases")
        for (index in 0 until casesArray.length()) {
            val case = casesArray.getJSONObject(index)
            val id = case.getString("id")
            val samples = signalFrom(case.getJSONObject("signal"), signalRate)
            val settingsJson = case.getJSONObject("settings")
            val settings = SampleEdit.StripSettings(
                thresholdDb = settingsJson.getDouble("thresholdDb").toFloat(),
                minSilenceMs = settingsJson.getDouble("minSilenceMs").toFloat(),
                minKeepMs = settingsJson.getDouble("minKeepMs").toFloat(),
                padMs = settingsJson.getDouble("padMs").toFloat(),
            )
            val expected = case.getJSONArray("expectSpans")
            val spans = SampleEdit.keepSpans(samples, 1, sampleRate, settings)
            assertEquals(expected.length(), spans.size, "$id: span count")
            for (i in 0 until expected.length()) {
                val pair = expected.getJSONArray(i)
                assertEquals(pair.getInt(0), spans[i].startFrame, "$id: span $i start")
                assertEquals(pair.getInt(1), spans[i].endFrameExclusive, "$id: span $i end")
            }
        }
    }

    @Test
    fun `normalize peaks at the contract reference level`() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val out = SampleEdit.normalize(samples, channels = 2)
        assertEquals(-0.3f, 20f * kotlin.math.log10(SampleEdit.peak(out, 2)), 0.01f)
        assertEquals(0.1f, samples[0], 1e-6f, "input buffer must not be mutated")
    }

    @Test
    fun `fade endpoints are exact on every channel`() {
        val frames = 100
        val samples = FloatArray(frames * 2) { 1f }
        val fadedIn = SampleEdit.fadeIn(samples, 2, 20)
        assertEquals(0f, fadedIn[0], 1e-6f)
        assertEquals(0f, fadedIn[1], 1e-6f)
        assertEquals(1f, fadedIn[19 * 2], 1e-5f)
        assertTrue(fadedIn[20 * 2] == 1f)

        val fadedOut = SampleEdit.fadeOut(samples, 2, 20)
        assertEquals(0f, fadedOut[(frames - 1) * 2], 1e-6f)
        assertEquals(0f, fadedOut[(frames - 1) * 2 + 1], 1e-6f)
        assertEquals(1f, fadedOut[(frames - 20) * 2], 1e-5f)
    }

    @Test
    fun `reverse keeps channels in place`() {
        val samples = floatArrayOf(0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f)
        val out = SampleEdit.reverse(samples, 2)
        assertEquals(0.3f, out[0], 1e-6f)
        assertEquals(-0.3f, out[1], 1e-6f)
        assertEquals(0.2f, out[2], 1e-6f)
        assertEquals(-0.2f, out[3], 1e-6f)
    }

    @Test
    fun `trim rebases the kept range`() {
        val samples = FloatArray(10 * 2) { it.toFloat() }
        val out = SampleEdit.trim(samples, 2, 2, 5)
        assertEquals(6, out.size)
        assertEquals(4f, out[0], 1e-6f)
        assertEquals(9f, out[5], 1e-6f)
    }

    @Test
    fun `dc removal subtracts each channel mean`() {
        val samples = floatArrayOf(1f, 3f, 1f, 3f, 1f, 3f)
        val out = SampleEdit.removeDcOffset(samples, 2)
        for (i in out.indices) assertEquals(0f, out[i], 1e-6f)
    }

    @Test
    fun `zero crossing search finds the nearest sign change`() {
        // 10 frames, 1 channel: alternating positive / negative every 4 frames.
        val samples = floatArrayOf(0.5f, 0.4f, 0.3f, 0.2f, -0.1f, -0.2f, -0.3f, -0.4f, 0.1f, 0.2f)
        assertEquals(3, SampleEdit.nearestZeroCrossing(samples, 1, 4, 8))
        assertEquals(3, SampleEdit.nearestZeroCrossing(samples, 1, 2, 8))
        assertEquals(1, SampleEdit.nearestZeroCrossing(floatArrayOf(1f, 1f, 1f), 1, 1, 8))
    }

    @Test
    fun `strip settings convert milliseconds at the session rate`() {
        assertEquals(480, SampleEdit.msToFrames(10f, 48000))
        assertEquals(16800, SampleEdit.msToFrames(350f, 48000))
        assertTrue(abs(SampleEdit.dbToLinear(-6f) - 0.501f) < 0.001f)
    }
}
