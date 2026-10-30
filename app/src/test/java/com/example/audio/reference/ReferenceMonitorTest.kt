package com.example.audio.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The A/B monitor decides whether the comparison is fair: the same position, the
 * same loudness, and no click when switching. Each test here fails if one of
 * those three breaks.
 */
class ReferenceMonitorTest {

    private val rate = 48000

    private fun constant(frames: Int, value: Float): FloatArray =
        FloatArray(frames * 2) { value }

    private fun impulseRef(frames: Int, vararg at: Int): FloatArray {
        val out = FloatArray(frames * 2)
        for (frame in at) {
            if (frame in 0 until frames) {
                out[frame * 2] = 0.5f
                out[frame * 2 + 1] = 0.5f
            }
        }
        return out
    }

    private fun rms(values: FloatArray, from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += values[i].toDouble() * values[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    @Test
    fun `without a reference the mix passes through untouched`() {
        val monitor = ReferenceMonitor(rate)
        val left = constant(256, 0.7f)
        val right = constant(256, -0.3f)
        monitor.setAudition(true)
        monitor.process(left, right, 256, 0L, false, 0L, 0L)
        assertFalse(monitor.isAuditioning)
        assertEquals(0.7f, left[0], 0f)
        assertEquals(-0.3f, right[255], 0f)
    }

    @Test
    fun `a full switch plays the reference at the matched gain`() {
        val monitor = ReferenceMonitor(rate)
        // Reference sits 6.02 dB under the mix; the match gain puts it back.
        monitor.setReference(constant(rate * 4, 0.5f))
        monitor.setMatchGainDb(6.0206f)
        monitor.setAudition(true)

        val left = constant(rate * 1, 1.0f)
        val right = constant(rate * 1, 1.0f)
        monitor.process(left, right, rate, 0L, false, 0L, 0L)

        assertEquals(1f, monitor.crossfade, 0.0001f)
        assertEquals("reference at matched gain should equal the mix level", 1.0f, left[rate - 1], 0.01f)
        assertEquals(1.0f, right[rate - 1], 0.01f)
    }

    @Test
    fun `the switch is click free`() {
        val monitor = ReferenceMonitor(rate)
        monitor.setReference(constant(rate * 2, 0.5f))
        monitor.setAudition(true)

        val left = constant(4096, 1.0f)
        val right = constant(4096, 1.0f)
        monitor.process(left, right, 4096, 0L, false, 0L, 0L)

        var maxStep = 0f
        for (i in 1 until 4096) {
            val step = abs(left[i] - left[i - 1])
            if (step > maxStep) maxStep = step
        }
        // 45 ms switch at 48 kHz: about 1/2160 of the 0.5 level difference per sample.
        assertTrue("largest single-sample jump was $maxStep", maxStep < 0.005f)
    }

    @Test
    fun `the equal power crossfade keeps the level steady through the switch`() {
        val monitor = ReferenceMonitor(rate)
        val random = java.util.Random(11)
        fun noise(): FloatArray = FloatArray(rate * 2) { ((random.nextFloat() * 2f - 1f) * 0.5f) }
        val mix = noise()
        val reference = noise()
        monitor.setReference(reference)
        monitor.setMatchGainDb(0f)
        monitor.setAudition(true)

        val left = mix.copyOf()
        val right = mix.copyOf()
        monitor.process(left, right, rate, 0L, false, 0L, 0L)

        val inputRms = rms(mix, 0, rate)
        var worstDb = 0.0
        var start = 0
        while (start + 512 <= rate) {
            val windowRms = rms(left, start, start + 512)
            val db = 20.0 * kotlin.math.log10(windowRms / inputRms)
            if (abs(db) > abs(worstDb)) worstDb = db
            start += 512
        }
        assertTrue("crossfade level wandered by $worstDb dB", abs(worstDb) < 0.7)
    }

    @Test
    fun `the reference plays at the transport position`() {
        val monitor = ReferenceMonitor(rate)
        monitor.setReference(impulseRef(rate * 4, 5000), offset = 0L)
        monitor.setAudition(true)

        // Pre-roll so the crossfade finishes first.
        val warmL = FloatArray(3000); val warmR = FloatArray(3000)
        monitor.process(warmL, warmR, 3000, 0L, false, 0L, 0L)

        val left = FloatArray(300); val right = FloatArray(300)
        monitor.process(left, right, 300, 4900L, false, 0L, 0L)

        // Reference frame 5000 is 100 frames into a block that starts at 4900.
        assertEquals(0.5f, left[100], 0.001f)
        assertEquals(0f, left[99], 0.001f)
        assertEquals(0f, left[101], 0.001f)
    }

    @Test
    fun `the offset aligns the reference to the session timeline`() {
        val monitor = ReferenceMonitor(rate)
        monitor.setReference(impulseRef(rate * 4, 5000), offset = 5000L)
        monitor.setAudition(true)
        val warmL = FloatArray(3000); val warmR = FloatArray(3000)
        monitor.process(warmL, warmR, 3000, 0L, false, 0L, 0L)

        // With a 5000-frame offset, session frame 0 is reference frame 5000.
        val left = FloatArray(64); val right = FloatArray(64)
        monitor.process(left, right, 64, 0L, false, 0L, 0L)
        assertEquals(0.5f, left[0], 0.001f)
    }

    @Test
    fun `with a loop the reference follows the same window and wraps with it`() {
        val monitor = ReferenceMonitor(rate)
        // Marker at reference frame 0 and at 1900.
        monitor.setReference(impulseRef(rate * 4, 0, 1000, 1900), offset = 0L)
        monitor.setAudition(true)
        val warmL = FloatArray(3000); val warmR = FloatArray(3000)
        monitor.process(warmL, warmR, 3000, 0L, false, 0L, 0L)

        // Inside the loop: song 11900 is 1900 frames into the 10000..12000 loop.
        val left = FloatArray(300); val right = FloatArray(300)
        monitor.process(left, right, 300, 11900L, true, 10000L, 12000L)
        assertEquals("reference frame 1900 lands at the loop position", 0.5f, left[0], 0.001f)
        // 100 frames later the loop wraps to its start, and so does the reference.
        assertEquals("the reference wraps with the loop", 0.5f, left[100], 0.001f)
    }

    @Test
    fun `a reference that ends goes silent instead of reading past the end`() {
        val monitor = ReferenceMonitor(rate)
        monitor.setReference(constant(1000, 0.5f))
        monitor.setAudition(true)
        val warmL = FloatArray(3000); val warmR = FloatArray(3000)
        monitor.process(warmL, warmR, 3000, 0L, false, 0L, 0L)

        val left = FloatArray(256); val right = FloatArray(256)
        monitor.process(left, right, 256, 48000L, false, 0L, 0L)
        for (v in left) assertEquals(0f, v, 0f)
        for (v in right) assertEquals(0f, v, 0f)
    }

    @Test
    fun `a new match gain ramps instead of jumping`() {
        val monitor = ReferenceMonitor(rate)
        monitor.setReference(constant(rate * 2, 0.25f))
        monitor.setMatchGainDb(0f)
        monitor.setAudition(true)
        val warmL = FloatArray(rate); val warmR = FloatArray(rate)
        monitor.process(warmL, warmR, rate, 0L, false, 0L, 0L)

        monitor.setMatchGainDb(12f)
        val left = FloatArray(8192); val right = FloatArray(8192)
        monitor.process(left, right, 8192, rate.toLong(), false, 0L, 0L)

        var maxStep = 0f
        for (i in 1 until left.size) {
            val step = abs(left[i] - left[i - 1])
            if (step > maxStep) maxStep = step
        }
        assertTrue("match gain change clicked: step $maxStep", maxStep < 0.01f)
        // And it does arrive (20 ms time constant): 0.25 * 10^(12/20) = 0.995
        assertEquals(0.995f, left[8191], 0.01f)
    }
}
