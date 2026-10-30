package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.plugins.ChainRole
import com.example.audio.plugins.MixBuses
import com.example.audio.plugins.MixReferenceTemplateLibrary
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginRackViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        viewModel = DawViewModel(application)
    }

    @Test
    fun applyingAReferenceTemplateChainsTracksBusesAndMixBus() {
        viewModel.applyMixTemplate("pop")

        val session = viewModel.session.value
        val kick = session.tracks[0]
        val vocal = session.tracks[13]
        assertTrue("kick must receive the pop kick chain", kick.pluginChain.isNotEmpty())
        assertEquals(MixBuses.DRUMS, kick.outputBusId)
        assertTrue("lead vocal must receive the pop vocal chain", vocal.pluginChain.isNotEmpty())
        // Every template leads with the gain-staging trim ("TRIM sits first and
        // stages the signal to -18 dBFS", STUDIO_PLUGIN_CHAINS.md), so the Neve
        // pre is the first module of the chain proper.
        assertEquals(PluginKind.TRIM, vocal.pluginChain.first().kind)
        assertEquals(PluginKind.NEVE_1073, vocal.pluginChain.first { it.kind != PluginKind.TRIM }.kind)
        assertTrue(
            "the pop vocal chain must include a 1176 and a de-esser",
            vocal.pluginChain.any { it.kind == PluginKind.FET_1176 } &&
                vocal.pluginChain.any { it.kind == PluginKind.DE_ESSER }
        )
        assertEquals("templates create the five group buses", 5, session.buses.size)
        assertTrue("drum bus must carry the template chain", session.buses.first { it.id == MixBuses.DRUMS }.pluginChain.isNotEmpty())
        assertEquals(
            MixReferenceTemplateLibrary.byId("pop")!!.mixBusChain.map { it.kind },
            session.masterPluginChain.map { it.kind }
        )
        assertTrue("template application must be undoable", viewModel.canUndo())
        assertTrue(
            "status must tell the engineer what happened",
            viewModel.uiState.value.statusMessage.orEmpty().contains("Modern Pop")
        )
    }

    @Test
    fun undoingATemplateRestoresThePreviousSession() {
        viewModel.applyMixTemplate("rock")
        assertTrue(viewModel.session.value.tracks.any { it.pluginChain.isNotEmpty() })

        viewModel.undo()

        val after = viewModel.session.value
        assertTrue("undo must clear the applied chains", after.tracks.all { it.pluginChain.isEmpty() })
        assertTrue("undo must clear the mix bus chain", after.masterPluginChain.isEmpty())
    }

    @Test
    fun editingATrackChainIsUndoableAndScopedToOneTrack() {
        val chain = listOf(
            PluginCatalog.defaultSlot(PluginKind.SSL_CHANNEL_EQ),
            PluginCatalog.defaultSlot(PluginKind.FET_1176)
        )
        viewModel.updateTrackPluginChain(4, chain)

        val session = viewModel.session.value
        assertEquals(2, session.tracks[4].pluginChain.size)
        assertEquals(PluginKind.SSL_CHANNEL_EQ, session.tracks[4].pluginChain[0].kind)
        assertTrue("no other track may change", session.tracks.filterIndexed { i, _ -> i != 4 }.all { it.pluginChain.isEmpty() })

        viewModel.undo()
        assertTrue("undo must remove the chain", viewModel.session.value.tracks[4].pluginChain.isEmpty())
    }

    @Test
    fun applyingOneRoleChainOnlyTouchesTheSelectedTrack() {
        viewModel.applyTemplateRoleToTrack(5, ChainRole.BASS, "house")

        val session = viewModel.session.value
        assertTrue("bass chain must be applied", session.tracks[5].pluginChain.isNotEmpty())
        assertEquals(MixBuses.BASS, session.tracks[5].outputBusId)
        assertTrue(
            "the house bass chain ducks against the kick",
            session.tracks[5].pluginChain.any { it.kind == PluginKind.SIDECHAIN_DUCK }
        )
        assertTrue("only the selected track changes", session.tracks.filterIndexed { i, _ -> i != 5 }.all { it.pluginChain.isEmpty() })
        assertTrue("no group buses are created for a single role", session.buses.isEmpty())
    }

    @Test
    fun outputBusRoutingIsStoredOnTheTrack() {
        viewModel.setTrackOutputBus(9, MixBuses.MUSIC)
        assertEquals(MixBuses.MUSIC, viewModel.session.value.tracks[9].outputBusId)
        assertFalse(viewModel.session.value.tracks[9].pluginChain.isNotEmpty())
    }

    @Test
    fun rackMetersReturnEmptyBeforeAChainIsLoaded() {
        assertTrue(
            "no rack meters until a chain exists",
            viewModel.trackSlotGainReductions(0).isEmpty()
        )
    }
}
