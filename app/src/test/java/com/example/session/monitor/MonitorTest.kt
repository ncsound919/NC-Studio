package com.example.session.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {

    private fun frame(seq: Int, kind: Byte = MonitorFrame.KIND_MONITOR): MonitorFrame.Frame =
        MonitorFrame.Frame(kind, seq, 1_000L + seq, byteArrayOf(1, 2, 3, seq.toByte()))

    @Test
    fun framesRoundTrip() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val encoded = MonitorFrame.encode(MonitorFrame.KIND_TALKBACK, 42, 123_456L, payload)
        val decoded = MonitorFrame.decode(encoded)
        assertNotNull(decoded)
        assertEquals(MonitorFrame.KIND_TALKBACK, decoded!!.kind)
        assertEquals(42, decoded.seq)
        assertEquals(123_456L, decoded.timestampMs)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun malformedFramesAreDropped() {
        assertNull(MonitorFrame.decode(ByteArray(0)))
        assertNull(MonitorFrame.decode(ByteArray(MonitorFrame.HEADER_BYTES))) // no payload
        assertNull(MonitorFrame.decode(ByteArray(MonitorFrame.MAX_FRAME_BYTES + 1)))
        val badKind = MonitorFrame.encode(99, 1, 2, byteArrayOf(1))
        assertNull(MonitorFrame.decode(badKind))
    }

    @Test
    fun jitterBufferPlaysInOrderAfterTheCushionFills() {
        val jitter = AudioJitterBuffer(targetDepth = 3)
        jitter.push(frame(2))
        jitter.push(frame(0))
        assertNull("no output until the cushion fills", jitter.pop())
        jitter.push(frame(1))

        // The cushion is full; the first pop starts playback in order.
        assertEquals(0, jitter.pop()?.seq)
        jitter.push(frame(3))
        assertEquals(1, jitter.pop()?.seq)
        jitter.push(frame(4))
        assertEquals(2, jitter.pop()?.seq)
        assertEquals(3, jitter.pop()?.seq)
        assertEquals(4, jitter.pop()?.seq)
    }

    @Test
    fun lateAndWildlyOutOfRangeFramesAreDropped() {
        val jitter = AudioJitterBuffer(targetDepth = 2, lateThresholdFrames = 8)
        repeat(3) { jitter.push(frame(it)) }
        assertEquals(0, jitter.pop()?.seq)
        assertEquals(1, jitter.pop()?.seq)

        jitter.push(frame(1)) // duplicate/late
        jitter.push(frame(25)) // far future
        assertEquals(2, jitter.droppedLate)
        assertEquals(2, jitter.pop()?.seq)
    }

    @Test
    fun permanentGapJumpsForwardInsteadOfStalling() {
        val jitter = AudioJitterBuffer(targetDepth = 2, lateThresholdFrames = 20)
        repeat(3) { jitter.push(frame(it)) }
        // Drain the cushion, then queue later frames without 3 and 4.
        assertEquals(0, jitter.pop()?.seq)
        assertEquals(1, jitter.pop()?.seq)
        assertEquals(2, jitter.pop()?.seq)
        jitter.push(frame(5))
        jitter.push(frame(6))
        jitter.push(frame(7))

        val jumped = jitter.pop()
        assertEquals(7, jumped?.seq)
        assertNull("buffer is empty after the jump", jitter.pop())
    }

    @Test
    fun overloadDropsOldestFramesWithoutGrowingUnbounded() {
        val jitter = AudioJitterBuffer(targetDepth = 2, maxDepth = 5)
        repeat(10) { jitter.push(frame(it)) }
        assertTrue(jitter.droppedOverflow > 0)
        val first = jitter.pop()
        assertNotNull(first)
        assertTrue(first!!.seq >= 5)
    }

}
