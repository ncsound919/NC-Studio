package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimecodeTest {

    private val rate = 48_000

    @Test
    fun format_readsLikeTheTransport() {
        assertEquals("00:00.00", Timecode.format(0L, rate))
        assertEquals("00:00.52", Timecode.format(25_000L, rate))
        assertEquals("00:01.00", Timecode.format(48_000L, rate))
        assertEquals("01:00.00", Timecode.format(2_880_000L, rate))
    }

    @Test
    fun parse_acceptsTheFormatsAnEngineerTypes() {
        assertEquals(0L, Timecode.parse("0", rate))
        assertEquals(48_000L, Timecode.parse("1", rate))
        assertEquals(48_000L, Timecode.parse("1.0", rate))
        assertEquals(48_000L, Timecode.parse("0:1", rate))
        assertEquals(2_942_400L, Timecode.parse("1:01.3", rate))
        assertEquals(3_000_000L, Timecode.parse("1:02.5", rate))
        assertEquals(2_880_000L, Timecode.parse("1:00", rate))
        assertEquals(24_000L, Timecode.parse(" 0,5 ", rate))
    }

    @Test
    fun parse_rejectsTextThatIsNotATime() {
        assertNull(Timecode.parse("", rate))
        assertNull(Timecode.parse("   ", rate))
        assertNull(Timecode.parse("one", rate))
        assertNull(Timecode.parse("1:2:3", rate))
        assertNull(Timecode.parse("1:", rate))
        assertNull(Timecode.parse(":30", rate))
        assertNull(Timecode.parse("-2", rate))
    }

    @Test
    fun parse_roundTripsWithinACentisecond() {
        val frames = 3_456_789L

        val parsed = Timecode.parse(Timecode.format(frames, rate), rate)

        assertTrue(parsed != null)
        // The field shows hundredths of a second, so a typed-back time lands
        // within half a centisecond of the original frame.
        assertTrue("parsed=$parsed", kotlin.math.abs(parsed!! - frames) <= 240L)
    }
}
