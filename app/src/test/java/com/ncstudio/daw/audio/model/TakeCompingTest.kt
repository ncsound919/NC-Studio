package com.ncstudio.daw.audio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeCompingTest {

    private fun clip(
        name: String = "Take",
        start: Long = 0L,
        length: Long = 48_000L,
        sourceStart: Long = 0L,
        fadeIn: Long = 0L,
        fadeOut: Long = 0L
    ) = AudioClip(
        name = name,
        sourcePath = "source.wav",
        sourceStartFrame = sourceStart,
        lengthFrames = length,
        timelineStartFrame = start,
        fadeInFrames = fadeIn,
        fadeOutFrames = fadeOut,
        trackIndex = 0
    )

    @Test
    fun compingAHoleBorrowsTheRangeAndSplitsTheActiveClip() {
        val active = listOf(clip(start = 0L, length = 48_000L))
        val source = listOf(clip(name = "Alt", start = 0L, length = 96_000L))

        val comped = active.withCompedRange(source, 12_000L, 24_000L)

        assertEquals("head, borrowed range, tail", 3, comped.size)
        assertEquals(listOf(0L, 12_000L, 24_000L), comped.map { it.timelineStartFrame })
        assertEquals(listOf(12_000L, 12_000L, 24_000L), comped.map { it.lengthFrames })
        val borrowed = comped[1]
        assertEquals("Borrowed audio keeps its position inside the take", 12_000L, borrowed.sourceStartFrame)
        assertEquals("Alt", borrowed.name)
    }

    @Test
    fun compingFillsSilenceWhenTheActiveTakeIsEmpty() {
        val source = listOf(clip(name = "Alt", start = 4_000L, length = 20_000L))

        val comped = emptyList<AudioClip>().withCompedRange(source, 0L, 48_000L)

        assertEquals(1, comped.size)
        assertEquals(4_000L, comped.single().timelineStartFrame)
        assertEquals(20_000L, comped.single().lengthFrames)
    }

    @Test
    fun compingATakeWithNoAudioThereTouchesNothing() {
        val active = listOf(clip(start = 0L, length = 48_000L))
        val source = listOf(clip(name = "Alt", start = 60_000L, length = 10_000L))

        val comped = active.withCompedRange(source, 0L, 48_000L)

        assertSame("An empty comp must not rewrite the clip list", active, comped)
    }

    @Test
    fun compingPartialCoverageKeepsTheRestOfTheAudibleTake() {
        val active = listOf(clip(start = 0L, length = 48_000L))
        val source = listOf(clip(name = "Alt", start = 20_000L, length = 10_000L))

        val comped = active.withCompedRange(source, 0L, 48_000L)

        assertEquals(3, comped.size)
        assertEquals(listOf(0L, 20_000L, 30_000L), comped.map { it.timelineStartFrame })
        assertEquals(listOf(20_000L, 10_000L, 18_000L), comped.map { it.lengthFrames })
    }

    @Test
    fun compingIgnoresAnEmptyOrReversedRange() {
        val active = listOf(clip())
        val source = listOf(clip(name = "Alt"))

        assertSame(active, active.withCompedRange(source, 1_000L, 1_000L))
        assertSame(active, active.withCompedRange(source, 5_000L, 1_000L))
    }

    @Test
    fun compedEdgesHaveNoInheritedFades() {
        val active = listOf(clip(start = 0L, length = 48_000L, fadeIn = 4_800L, fadeOut = 4_800L))
        val source = listOf(clip(name = "Alt", start = 0L, length = 48_000L, fadeIn = 9_600L, fadeOut = 9_600L))

        val comped = active.withCompedRange(source, 12_000L, 24_000L)

        val borrowed = comped.first { it.name == "Alt" }
        assertEquals("A cut boundary must be sample accurate", 0L, borrowed.fadeInFrames)
        assertEquals(0L, borrowed.fadeOutFrames)
        val head = comped.first { it.timelineStartFrame == 0L }
        assertEquals("The original start keeps its fade-in", 4_800L, head.fadeInFrames)
        assertEquals(0L, head.fadeOutFrames)
        val tail = comped.first { it.timelineStartFrame == 24_000L }
        assertEquals(0L, tail.fadeInFrames)
        assertEquals("The original end keeps its fade-out", 4_800L, tail.fadeOutFrames)
    }

    @Test
    fun compingOnlyPartOfTheSourceTrimsItToTheRange() {
        val active = emptyList<AudioClip>()
        val source = listOf(clip(name = "Alt", start = 0L, length = 48_000L))

        val comped = active.withCompedRange(source, 10_000L, 30_000L)

        assertEquals(1, comped.size)
        assertEquals(20_000L, comped.single().lengthFrames)
        assertEquals(10_000L, comped.single().sourceStartFrame)
        assertTrue(comped.single().timelineStartFrame == 10_000L)
    }
}
