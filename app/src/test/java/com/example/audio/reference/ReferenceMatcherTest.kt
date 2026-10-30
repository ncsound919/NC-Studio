package com.example.audio.reference

import com.example.audio.model.ProjectSession
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMatcherTest {

    private val bandCount = SpectrumBalanceAnalyzer.BAND_CENTERS_HZ.size

    private fun profile(
        lufs: Float,
        truePeak: Float = -1f,
        bands: List<Float> = List(bandCount) { -10f },
        width: Float = -6f
    ) = ReferenceProfile(
        label = "test",
        integratedLufs = lufs,
        truePeakDb = truePeak,
        bandBalanceDb = bands,
        widthDb = width
    )

    private fun bandsWith(vararg overrides: Pair<Int, Float>): List<Float> {
        val bands = MutableList(bandCount) { -10f }
        overrides.forEach { (index, value) -> bands[index] = value }
        return bands
    }

    @Test
    fun `a mix that already matches the reference produces no moves`() {
        val reference = profile(-9f)
        val report = ReferenceMatcher.compare(profile(-9.1f), reference)
        assertTrue("no move should survive the deadbands: ${report.moves}", report.moves.isEmpty())
        // levelDelta is reference minus mix: the reference is 0.1 LU louder here.
        assertEquals(0.1f, report.levelDeltaLu, 0.001f)
    }

    @Test
    fun `a quieter mix gets a bounded level move with the projected true peak in the note`() {
        val report = ReferenceMatcher.compare(
            profile(-16f, truePeak = -6f),
            profile(-9f)
        )
        val level = report.moves.single { it.kind == ReferenceMoveKind.LEVEL }
        assertEquals(7f, level.amountDb, 0.01f)
        assertTrue("the note must state the projected peak", level.detail.contains("dBTP"))
        assertEquals(-9f, report.mix.integratedLufs + level.amountDb, 0.01f)
    }

    @Test
    fun `the level move is clamped and a silent mix is never moved`() {
        val loud = ReferenceMatcher.compare(profile(-40f, truePeak = -30f), profile(-6f))
        val level = loud.moves.single { it.kind == ReferenceMoveKind.LEVEL }
        assertEquals(ReferenceMatcher.MAX_LEVEL_MOVE_DB, level.amountDb, 0.01f)

        val silentReport = ReferenceMatcher.compare(
            profile(-60f, truePeak = -120f),
            profile(-6f)
        )
        assertTrue("a silent mix gets no suggestions", silentReport.moves.isEmpty())
    }

    @Test
    fun `a heavy low end produces a cut and a thin one produces a boost`() {
        val heavy = ReferenceMatcher.compare(
            profile(-9f, bands = bandsWith(1 to -6f, 2 to -6f, 3 to -6f)),
            profile(-9f)
        )
        val lowCut = heavy.moves.single { it.kind == ReferenceMoveKind.LOW_SHELF }
        assertTrue("hotter low end must be a cut", lowCut.amountDb < -1f)

        val thin = ReferenceMatcher.compare(
            profile(-9f, bands = bandsWith(1 to -14f, 2 to -14f, 3 to -14f)),
            profile(-9f)
        )
        val lowBoost = thin.moves.single { it.kind == ReferenceMoveKind.LOW_SHELF }
        assertTrue("a thinner low end must be a boost", lowBoost.amountDb > 1f)
    }

    @Test
    fun `shelf moves are clamped well short of a remix`() {
        val report = ReferenceMatcher.compare(
            profile(-9f, bands = bandsWith(1 to 0f, 2 to 0f, 3 to 0f, 7 to 0f, 8 to 0f)),
            profile(-9f, bands = List(bandCount) { -30f })
        )
        report.moves.filter { it.kind == ReferenceMoveKind.LOW_SHELF || it.kind == ReferenceMoveKind.HIGH_SHELF }
            .forEach { move ->
                assertTrue("${move.title} was ${move.amountDb} dB", kotlin.math.abs(move.amountDb) <= 6f)
            }
    }

    @Test
    fun `a narrower mix gets a width move inside the ratio bounds`() {
        val narrower = ReferenceMatcher.compare(profile(-9f, width = -12f), profile(-9f, width = -4f))
        val widen = narrower.moves.single { it.kind == ReferenceMoveKind.WIDTH }
        assertTrue("widen ratio must be above 1", widen.amountDb > 1f && widen.amountDb <= 1.75f)

        val muchNarrower = ReferenceMatcher.compare(profile(-9f, width = -40f), profile(-9f, width = 0f))
        val clamped = muchNarrower.moves.single { it.kind == ReferenceMoveKind.WIDTH }
        assertEquals(1.75f, clamped.amountDb, 0.001f)
    }

    // --------------------------------------------------------------- applying

    @Test
    fun `applying moves sets the master gain and writes one tone slot plus a limiter`() {
        val session = ProjectSession(projectName = "test")
        val report = ReferenceMatcher.compare(
            profile(-16f, truePeak = -6f, bands = bandsWith(1 to -6f, 2 to -6f, 3 to -6f, 7 to -14f, 8 to -14f)),
            profile(-9f)
        )
        val after = ReferenceMatcher.applyToSession(session, report.moves)

        assertEquals(7f, after.masterGainDb, 0.01f)

        val tone = after.masterPluginChain.single { it.id == ReferenceMatcher.TONE_SLOT_ID }
        assertEquals(PluginKind.SSL_CHANNEL_EQ, tone.kind)
        assertTrue("low end was hot, so the low shelf must cut", tone.value("lfDb") < -1f)
        assertTrue("top end was thin, so the high shelf must boost", tone.value("hfDb") > 1f)
        assertEquals(100f, tone.value("lfHz"), 0.01f)
        assertEquals(10000f, tone.value("hfHz"), 0.01f)

        assertTrue(
            "an active mix chain takes over bus processing, so it needs a limiter",
            after.masterPluginChain.any { it.kind == PluginKind.TRUE_PEAK_LIMITER }
        )
        assertTrue(
            "the limiter must come last",
            after.masterPluginChain.last().kind == PluginKind.TRUE_PEAK_LIMITER
        )
        assertEquals(session.revision + 1, after.revision)
    }

    @Test
    fun `repeated matches update the same slots instead of stacking new ones`() {
        val session = ProjectSession(projectName = "test")
        val first = ReferenceMatcher.applyToSession(
            session,
            ReferenceMatcher.compare(
                profile(-16f, bands = bandsWith(1 to -6f, 2 to -6f, 3 to -6f)),
                profile(-9f)
            ).moves
        )
        val second = ReferenceMatcher.applyToSession(
            first,
            ReferenceMatcher.compare(
                profile(-12f, width = -14f, bands = bandsWith(1 to -8f, 2 to -8f, 3 to -8f)),
                profile(-9f, width = -6f)
            ).moves
        )

        assertEquals(1, second.masterPluginChain.count { it.id == ReferenceMatcher.TONE_SLOT_ID })
        assertEquals(1, second.masterPluginChain.count { it.id == ReferenceMatcher.WIDTH_SLOT_ID })
        assertEquals(1, second.masterPluginChain.count { it.kind == PluginKind.TRUE_PEAK_LIMITER })
        val tone = second.masterPluginChain.single { it.id == ReferenceMatcher.TONE_SLOT_ID }
        assertTrue("the slot must carry the latest amount", tone.value("lfDb") < -1f)
    }

    @Test
    fun `a session with a chain already keeps its own limiter and order`() {
        val existingLimiter = PluginCatalog.defaultSlot(PluginKind.TRUE_PEAK_LIMITER)
        val session = ProjectSession(
            projectName = "test",
            masterPluginChain = listOf(existingLimiter)
        )
        val report = ReferenceMatcher.compare(
            profile(-9f, bands = bandsWith(7 to -6f, 8 to -6f)),
            profile(-9f)
        )
        val after = ReferenceMatcher.applyToSession(session, report.moves)

        assertEquals(1, after.masterPluginChain.count { it.kind == PluginKind.TRUE_PEAK_LIMITER })
        assertTrue(
            "tone shaping goes before the limiter",
            after.masterPluginChain.indexOfLast { it.id == ReferenceMatcher.TONE_SLOT_ID } <
                after.masterPluginChain.indexOfFirst { it.kind == PluginKind.TRUE_PEAK_LIMITER }
        )
        assertEquals("the limiter stays last", PluginKind.TRUE_PEAK_LIMITER, after.masterPluginChain.last().kind)
    }

    @Test
    fun `applying nothing leaves the session untouched`() {
        val session = ProjectSession(projectName = "test")
        assertEquals(session, ReferenceMatcher.applyToSession(session, emptyList()))
    }
}
