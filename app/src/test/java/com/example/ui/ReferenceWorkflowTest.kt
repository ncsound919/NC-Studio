package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.history.ProjectPersistence
import com.overlay.audio.plugins.PluginKind
import com.example.audio.reference.ReferenceMatcher
import com.example.audio.reference.ReferenceMoveKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

/**
 * The reference workflow end to end at the ViewModel level: import a file (the
 * decoding step is bypassed with the engine-rate samples), measure it, measure
 * the mix, level match the A/B, produce and apply the moves, and survive a
 * project save/load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReferenceWorkflowTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        viewModel = DawViewModel(application)
        // A 1 s 1 kHz sine at -6 dBFS on track 0, so the mix is measurable.
        viewModel.installTestClip(0, withAudio = true)
    }

    /** Engine-rate stereo reference: a sine at [amplitude] (-20.9 dBFS peak for 0.09). */
    private fun referenceTone(amplitude: Float = 0.09f, seconds: Float = 4f): FloatArray {
        val frames = (48000f * seconds).toInt()
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (amplitude * sin(2.0 * PI * 220.0 * i / 48000.0)).toFloat()
            out[i * 2] = sample
            out[i * 2 + 1] = sample
        }
        return out
    }

    @Test
    fun `loading a reference measures it, measures the mix and level matches the ab`() = runBlocking {
        val reference = referenceTone()
        viewModel.installReferenceNow(reference, "Test Reference", "/tmp/reference.wav")

        val state = viewModel.uiState.value
        assertTrue("the reference must be loaded", state.hasReferenceTrack)
        assertEquals("Test Reference", state.referenceName)
        val profile = state.referenceProfile
        assertNotNull("the reference must be analysed", profile)

        // A -20.9 dBFS peak, dual-mono 220 Hz sine: 0.09/root2 RMS summed over
        // two channels, less the -0.691 offset, is about -21.6 LUFS.
        assertTrue("reference loudness was ${profile!!.integratedLufs}", profile.integratedLufs in -22.2f..-21.0f)

        val mix = state.mixProfile
        assertNotNull("the mix must have been measured", mix)
        // A -6 dBFS sine mono source at centre pan: constant-power panning takes
        // 3 dB off each side, and the track strip and master limiter are in the
        // path, so the measured mix sits near -10.5 LUFS.
        assertTrue("mix loudness was ${mix!!.integratedLufs}", mix.integratedLufs in -12f..-9f)

        val config = viewModel.session.value.reference
        assertNotNull("the reference must be part of the session", config)
        assertEquals("Test Reference", config!!.name)
        assertEquals("/tmp/reference.wav", config.filePath)
        // The A/B gain is the mix loudness minus the reference loudness, so the
        // reference comes back down to the mix level.
        val expected = (mix.integratedLufs - profile.integratedLufs).coerceIn(-48f, 48f)
        assertEquals(expected, config.matchGainDb, 0.01f)
        assertTrue(
            "the quieter reference is lifted to the mix's loudness, not the other way round",
            config.matchGainDb > 10f
        )
    }

    @Test
    fun `the ab toggle is refused without a reference and alternates with one`() = runBlocking {
        viewModel.toggleAbComparison()
        assertFalse("no reference loaded yet", viewModel.uiState.value.isReferenceAuditioning)
        assertTrue(viewModel.uiState.value.statusKind == StatusKind.ERROR)

        viewModel.installReferenceNow(referenceTone(), "Ref", "/tmp/reference.wav")
        viewModel.toggleAbComparison()
        assertTrue(viewModel.uiState.value.isReferenceAuditioning)
        assertTrue(viewModel.session.value.reference!!.auditionEnabled)

        viewModel.toggleAbComparison()
        assertFalse(viewModel.uiState.value.isReferenceAuditioning)
        assertFalse(viewModel.session.value.reference!!.auditionEnabled)
    }

    @Test
    fun `matching moves the mix to the reference loudness and keeps the ab level matched`() = runBlocking {
        viewModel.installReferenceNow(referenceTone(), "Quiet Ref", "/tmp/reference.wav")
        val before = viewModel.session.value.masterGainDb
        val referenceLufs = viewModel.uiState.value.referenceProfile!!.integratedLufs
        val referenceMixLufs = viewModel.uiState.value.mixProfile!!.integratedLufs

        viewModel.matchToReferenceNow()

        val after = viewModel.session.value.masterGainDb
        val requested = referenceLufs - referenceMixLufs
        val expectedMove = requested.coerceIn(
            -ReferenceMatcher.MAX_LEVEL_MOVE_DB,
            ReferenceMatcher.MAX_LEVEL_MOVE_DB
        )
        val applied = after - before
        assertTrue("the reference is quieter, so the mix comes down", after < before)
        assertEquals("the move is the LUFS difference, bounded", expectedMove, applied, 0.05f)

        // The move covered the whole gap, so the reference now plays at unity:
        // both sides measure the same loudness and the A/B needs no correction.
        val auditionGain = viewModel.session.value.reference!!.matchGainDb
        assertEquals("the remainder is exactly the clamped part", applied - requested, auditionGain, 0.1f)
        assertEquals("an uncovered gap leaves nothing to correct", 0f, auditionGain, 0.1f)
        assertNotNull(viewModel.uiState.value.referenceMatch)
    }

    @Test
    fun `a move the matcher cannot cover is left to the audition gain`() = runBlocking {
        // 20 dB under the loud reference: the level move clamps at 12 dB, and the
        // remaining 8 dB has to be carried by the reference's own A/B gain, or the
        // comparison would be level matched in name only.
        viewModel.installReferenceNow(referenceTone(amplitude = 0.009f), "Very Quiet Ref", "/tmp/ref.wav")
        val mixLufs = viewModel.uiState.value.mixProfile!!.integratedLufs
        val referenceLufs = viewModel.uiState.value.referenceProfile!!.integratedLufs

        viewModel.matchToReferenceNow()

        val requested = referenceLufs - mixLufs
        val applied = viewModel.session.value.masterGainDb
        assertEquals(ReferenceMatcher.MAX_LEVEL_MOVE_DB, -applied, 0.05f)
        val auditionGain = viewModel.session.value.reference!!.matchGainDb
        assertEquals("the remainder is what the A/B compensates", applied - requested, auditionGain, 0.1f)
        assertTrue("the reference still comes up to the mix's loudness", auditionGain > 5f)
    }

    @Test
    fun `the reference check produces moves and applying them is one undoable step`() = runBlocking {
        viewModel.installReferenceNow(referenceTone(), "Ref", "/tmp/reference.wav")
        viewModel.refreshReferenceMatchNow()

        val report = viewModel.uiState.value.referenceMatch
        assertNotNull(report)
        assertTrue("a 220 Hz reference against a 1 kHz mix must suggest something", report!!.moves.isNotEmpty())
        assertTrue(report.moves.any { it.kind == ReferenceMoveKind.LEVEL })

        val before = viewModel.session.value
        viewModel.applyReferenceMoves()
        val after = viewModel.session.value

        assertTrue("the master gain must move", after.masterGainDb != before.masterGainDb)
        assertTrue(
            "the tone match writes the mix bus slot",
            after.masterPluginChain.any { it.id == ReferenceMatcher.TONE_SLOT_ID }
        )
        assertTrue(
            "an active mix chain needs a limiter",
            after.masterPluginChain.any { it.kind == PluginKind.TRUE_PEAK_LIMITER }
        )

        assertTrue("applying must be undoable", viewModel.canUndo())
        viewModel.undo()
        assertEquals("undo restores the master gain", before.masterGainDb, viewModel.session.value.masterGainDb, 0.001f)
        assertTrue(
            "undo clears the tone match slot",
            viewModel.session.value.masterPluginChain.none { it.id == ReferenceMatcher.TONE_SLOT_ID }
        )
    }

    @Test
    fun `clearing the reference empties the session slot and the ab state`() = runBlocking {
        viewModel.installReferenceNow(referenceTone(), "Ref", "/tmp/reference.wav")
        viewModel.toggleAbComparison()
        viewModel.clearReference()

        val state = viewModel.uiState.value
        assertFalse(state.hasReferenceTrack)
        assertFalse(state.isReferenceAuditioning)
        assertNull(state.referenceProfile)
        assertNull(state.referenceMatch)
        assertNull("the session must forget the reference", viewModel.session.value.reference)
    }

    @Test
    fun `a reference survives a project save and load`() = runBlocking {
        viewModel.installReferenceNow(referenceTone(), "Saved Ref", "/tmp/reference.wav")
        val session = viewModel.session.value
        val persistence = ProjectPersistence(application)

        val saved = persistence.saveProject(session, "reference_roundtrip.json")
        assertTrue("the project file must be written", saved.exists())
        val reloaded = persistence.loadProject(saved)

        assertNotNull(reloaded)
        val reference = reloaded!!.reference
        assertNotNull("the reference config must survive Moshi", reference)
        assertEquals("Saved Ref", reference!!.name)
        assertEquals(session.reference!!.profile.integratedLufs, reference.profile.integratedLufs, 0.001f)
        assertEquals(session.reference!!.profile.bandBalanceDb.size, reference.profile.bandBalanceDb.size)
        assertEquals(session.reference!!.matchGainDb, reference.matchGainDb, 0.001f)
    }
}
