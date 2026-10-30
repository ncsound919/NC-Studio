package com.example.audio.plugins

import com.example.audio.model.ProjectSession
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The templates are supposed to be a faithful translation of the reference
 * mixing material that ships with this project (mixing_settings.json plus the
 * guide and bible). These tests pin the reference points - records, LUFS
 * targets, tempo ranges and the plugin order per source - so a future edit
 * cannot quietly drift away from the references.
 */
class MixReferenceTemplateTest {

    private fun track(index: Int, name: String, category: StemCategory): TrackData =
        TrackData(index = index, name = name, colorHex = 0xFF000000, stemCategory = category)

    /** The 16 default session tracks, named exactly as the app names them. */
    private fun defaultSession(): ProjectSession {
        val names = listOf(
            "Kick Drum" to StemCategory.DRUMS,
            "Snare & Clap" to StemCategory.DRUMS,
            "Hi-Hats Groove" to StemCategory.DRUMS,
            "Toms & Percs" to StemCategory.DRUMS,
            "808 Sub Bass" to StemCategory.BASS,
            "Synth Bass" to StemCategory.BASS,
            "Rhythm Guitar L" to StemCategory.GUITAR,
            "Rhythm Guitar R" to StemCategory.GUITAR,
            "Lead Guitar" to StemCategory.GUITAR,
            "Acoustic Piano" to StemCategory.KEYS,
            "Rhodes E-Piano" to StemCategory.KEYS,
            "Synth Pad" to StemCategory.KEYS,
            "Synth Lead" to StemCategory.KEYS,
            "Lead Vocal" to StemCategory.VOCAL,
            "Vocal Harmonies" to StemCategory.VOCAL,
            "FX Riser & Drops" to StemCategory.OTHER
        )
        return ProjectSession(tracks = names.mapIndexed { i, (name, cat) -> track(i, name, cat) })
    }

    private fun template(id: String): MixReferenceTemplate =
        MixReferenceTemplateLibrary.byId(id) ?: error("missing template $id")

    private fun kinds(chain: List<PluginSlot>): List<PluginKind> = chain.map { it.kind }

    // ---------------------------------------------------------------
    // Reference fidelity
    // ---------------------------------------------------------------

    @Test
    fun everyTemplateIsWellFormedAndUsesCatalogParameters() {
        assertTrue("expected the full reference library", MixReferenceTemplateLibrary.ALL.size >= 10)
        for (template in MixReferenceTemplateLibrary.ALL) {
            assertTrue("${template.id} needs a reference record", template.referenceTrack.isNotBlank())
            assertTrue("${template.id} needs a credit", template.referenceCredit.isNotBlank())
            assertTrue("${template.id} needs chains", template.roleChains.isNotEmpty())
            assertTrue("${template.id} tempo range", template.tempoRange.first < template.tempoRange.last)
            assertTrue("${template.id} LUFS target", template.lufsTarget.first < template.lufsTarget.second)
            for ((role, chain) in template.roleChains) {
                assertTrue("${template.id}/${role.name} chain must not be empty", chain.isNotEmpty())
                for (slot in chain) {
                    val known = PluginCatalog.params(slot.kind).map { it.key }.toSet()
                    for (key in slot.params.keys) {
                        assertTrue(
                            "${template.id}/${role.name}: ${slot.kind.name} has unknown parameter '$key'",
                            key in known
                        )
                    }
                }
            }
        }
    }

    @Test
    fun genreTemplatesCarryTheReferenceTargetsFromMixingSettings() {
        val pop = template("pop")
        assertEquals("Flowers - Miley Cyrus", pop.referenceTrack)
        assertEquals(100..130, pop.tempoRange)
        assertEquals(-8f, pop.lufsTarget.first, 0.001f)
        assertEquals(-6f, pop.lufsTarget.second, 0.001f)

        val classic = template("hip_hop_classic")
        assertEquals("Nuthin' but a 'G' Thang - Dr. Dre ft. Snoop Dogg", classic.referenceTrack)
        assertEquals(85..100, classic.tempoRange)
        assertEquals(-9f, classic.lufsTarget.first, 0.001f)

        val trap = template("trap_drill")
        assertEquals("Sicko Mode - Travis Scott ft. Drake", trap.referenceTrack)
        assertEquals(130..160, trap.tempoRange)
        assertEquals(-5f, trap.lufsTarget.second, 0.001f)

        val house = template("house")
        assertEquals("Show Me Love - Robin S. (StoneBridge Mix)", house.referenceTrack)
        assertEquals(120..130, house.tempoRange)

        val rnb = template("rnb")
        assertEquals("Leave the Door Open - Silk Sonic", rnb.referenceTrack)
        assertEquals(60..110, rnb.tempoRange)

        val gospel = template("gospel")
        assertEquals("Never Would Have Made It - Marvin Sapp", gospel.referenceTrack)
        assertEquals(-12f, gospel.lufsTarget.first, 0.001f)

        val rock = template("rock")
        assertEquals("Smells Like Teen Spirit - Nirvana", rock.referenceTrack)
        assertEquals(100..160, rock.tempoRange)
    }

    @Test
    fun popVocalChainFollowsTheReferenceOrder() {
        val vocals = kinds(template("pop").roleChains.getValue(ChainRole.LEAD_VOCAL))
        val neve = vocals.indexOf(PluginKind.NEVE_1073)
        val fet = vocals.indexOf(PluginKind.FET_1176)
        val opto = vocals.indexOf(PluginKind.OPTO_LA2A)
        val deEss = vocals.indexOf(PluginKind.DE_ESSER)
        val air = vocals.indexOf(PluginKind.AIR_SHELF)
        assertTrue("Neve must come first", neve in 0..1)
        assertTrue("1176 after the preamp", fet > neve)
        assertTrue("LA-2A after the 1176 (edge then smooth)", opto > fet)
        assertTrue("de-esser after the compressors", deEss > opto)
        assertTrue("air shelf near the end", air > deEss)
    }

    @Test
    fun kickChainsPairAConsoleEqWithAPultecAndTheBusChainsMatchTheReference() {
        for (id in listOf("pop", "hip_hop_classic", "house")) {
            val kick = kinds(template(id).roleChains.getValue(ChainRole.KICK))
            assertTrue("$id kick must use the SSL channel EQ", PluginKind.SSL_CHANNEL_EQ in kick)
            assertTrue("$id kick must use the Pultec", PluginKind.PULTEC_EQP1A in kick)
        }
        for (id in listOf("pop", "hip_hop_classic", "house", "rnb", "gospel", "rock")) {
            val bus = kinds(template(id).mixBusChain)
            assertTrue("$id mix bus needs bus glue", PluginKind.VCA_BUS_COMP in bus)
            assertTrue("$id mix bus needs tape", PluginKind.TAPE_MACHINE in bus)
            assertEquals("$id mix bus: bus comp then tape", PluginKind.VCA_BUS_COMP, bus.first())
        }
        // Trap is the loud, limited one: it adds a true peak limiter.
        assertTrue(PluginKind.TRUE_PEAK_LIMITER in kinds(template("trap_drill").mixBusChain))

        // Drum bus parallel compression where the reference calls for it.
        assertTrue(kinds(template("pop").drumBusChain).contains(PluginKind.PARALLEL_COMP))
        assertTrue(kinds(template("rock").drumBusChain).contains(PluginKind.PARALLEL_COMP))
        assertTrue(kinds(template("hip_hop_classic").drumBusChain).isEmpty())
    }

    @Test
    fun genreSpecificToolsArePresent() {
        // House ducks bass and pads against the kick.
        assertTrue(kinds(template("house").roleChains.getValue(ChainRole.BASS)).contains(PluginKind.SIDECHAIN_DUCK))
        // Trap saturates the 808 and ducks it.
        val trap808 = kinds(template("trap_drill").roleChains.getValue(ChainRole.SUB_808))
        assertTrue(trap808.contains(PluginKind.SATURATION))
        assertTrue(trap808.contains(PluginKind.SIDECHAIN_DUCK))
        // Rock vocal gets slapback; gospel gets a long hall for the choir.
        assertTrue(kinds(template("rock").roleChains.getValue(ChainRole.LEAD_VOCAL)).contains(PluginKind.TEMPO_DELAY))
        assertTrue(kinds(template("gospel").roleChains.getValue(ChainRole.HARMONY)).contains(PluginKind.HALL_REVERB))
        // R&B harmonises wide.
        assertTrue(kinds(template("rnb").roleChains.getValue(ChainRole.HARMONY)).contains(PluginKind.MID_SIDE_EQ))
        // The staged-vocal engineer template stacks three compressors.
        val staged = kinds(template("engineer_staged_vocal").roleChains.getValue(ChainRole.LEAD_VOCAL))
        assertTrue(PluginKind.PULTEC_EQP1A in staged)
        assertTrue(PluginKind.FET_1176 in staged)
        assertTrue(PluginKind.VARI_MU_COMP in staged)
    }

    // ---------------------------------------------------------------
    // Role matching
    // ---------------------------------------------------------------

    @Test
    fun roleMatchingPicksTheMostSpecificRoleForEachDefaultTrack() {
        assertEquals(ChainRole.KICK, ChainRole.forTrackName("Kick Drum"))
        assertEquals(ChainRole.SNARE, ChainRole.forTrackName("Snare & Clap"))
        assertEquals(ChainRole.HATS, ChainRole.forTrackName("Hi-Hats Groove"))
        assertEquals(ChainRole.PERC, ChainRole.forTrackName("Toms & Percs"))
        assertEquals(ChainRole.SUB_808, ChainRole.forTrackName("808 Sub Bass"))
        assertEquals(ChainRole.BASS, ChainRole.forTrackName("Synth Bass"))
        assertEquals(ChainRole.GUITARS, ChainRole.forTrackName("Rhythm Guitar L"))
        assertEquals(ChainRole.KEYS, ChainRole.forTrackName("Acoustic Piano"))
        assertEquals(ChainRole.KEYS, ChainRole.forTrackName("Synth Pad"))
        assertEquals(ChainRole.LEAD_VOCAL, ChainRole.forTrackName("Lead Vocal"))
        // Harmony must win over the generic "vocal" alias.
        assertEquals(ChainRole.HARMONY, ChainRole.forTrackName("Vocal Harmonies"))
        assertEquals(ChainRole.FX, ChainRole.forTrackName("FX Riser & Drops"))
    }

    // ---------------------------------------------------------------
    // Application
    // ---------------------------------------------------------------

    @Test
    fun applyingAPopTemplateChainsTheMatchingTracksAndRoutesTheBuses() {
        val session = defaultSession()
        val after = TemplateApplier.applyToSession(session, template("pop"))

        val kick = after.tracks[0]
        val snare = after.tracks[1]
        val hats = after.tracks[2]
        val sub = after.tracks[4]
        val bass = after.tracks[5]
        val guitar = after.tracks[6]
        val piano = after.tracks[9]
        val vocal = after.tracks[13]
        val harmony = after.tracks[14]
        val fx = after.tracks[15]

        assertTrue("kick must be chained", kick.pluginChain.isNotEmpty())
        assertEquals(ChainRole.KICK, TemplateApplier.matchRole(kick))
        assertEquals(MixBuses.DRUMS, kick.outputBusId)
        assertEquals(MixBuses.DRUMS, snare.outputBusId)
        assertEquals(MixBuses.DRUMS, hats.outputBusId)
        assertEquals(MixBuses.BASS, sub.outputBusId)
        assertEquals(MixBuses.BASS, bass.outputBusId)
        assertEquals(MixBuses.MUSIC, guitar.outputBusId)
        assertEquals(MixBuses.MUSIC, piano.outputBusId)
        assertEquals(MixBuses.VOCAL, vocal.outputBusId)
        assertEquals(MixBuses.VOCAL, harmony.outputBusId)
        assertEquals(MixBuses.FX, fx.outputBusId)

        // FX has no chain in the pop template, so it keeps the legacy strip.
        assertTrue("FX track has no pop chain", fx.pluginChain.isEmpty())
        // Chain modules own the tone, so the legacy reverb send is cleared.
        assertEquals(0f, vocal.reverbSendLevel, 0.001f)

        // Group buses exist with the drum bus chain, and the mix bus is set.
        assertEquals(5, after.buses.size)
        val drumBus = after.buses.first { it.id == MixBuses.DRUMS }
        assertTrue("drum bus must carry the template chain", drumBus.pluginChain.isNotEmpty())
        assertEquals(
            template("pop").drumBusChain.map { it.kind },
            drumBus.pluginChain.map { it.kind }
        )
        assertEquals(template("pop").mixBusChain.map { it.kind }, after.masterPluginChain.map { it.kind })
    }

    @Test
    fun templatesLeaveTrackRolesTheyDoNotCoverAlone() {
        val session = defaultSession()
        val trap = template("trap_drill")
        assertFalse("trap has no guitar chain", trap.roleChains.containsKey(ChainRole.GUITARS))
        val after = TemplateApplier.applyToSession(session, trap)
        val guitar = after.tracks[6]
        assertTrue("guitar must be untouched by the trap template", guitar.pluginChain.isEmpty())
        // Routing still follows the role: guitars live on the music bus, the
        // template just has no guitar chain to install.
        assertEquals(MixBuses.MUSIC, guitar.outputBusId)
        // And a track that the template does cover is chained.
        assertTrue(after.tracks[4].pluginChain.isNotEmpty())
    }

    @Test
    fun applyingOneRoleChainOnlyTouchesThatTrack() {
        val session = defaultSession()
        val before = session.tracks[6]
        val after = TemplateApplier.applyRoleToTrack(before, ChainRole.LEAD_VOCAL, template("pop"))
        assertTrue(after.pluginChain.isNotEmpty())
        assertEquals(ChainRole.LEAD_VOCAL.displayName, "Lead Vocal")
        // Every other track in the session is untouched because only one track
        // was passed in, and the applier never mutates shared state.
        assertEquals(16, session.tracks.size)
        assertTrue(session.tracks.all { it.pluginChain.isEmpty() })
        assertEquals(MixBuses.VOCAL, after.outputBusId)
    }

    @Test
    fun matchedTrackCountReportsWhatATemplateWillChange() {
        val session = defaultSession()
        // Pop chains 9 roles: kick, snare, hats, 808, bass, guitars, keys,
        // lead vocal, harmonies. In the 16-track default session that is 14
        // tracks (three guitars, four keys tracks); percussion and FX are
        // recognised roles the template does not process.
        assertEquals(14, TemplateApplier.matchedTrackCount(session, template("pop")))
        // Trap omits guitars but covers everything else it knows a role for.
        assertTrue(TemplateApplier.matchedTrackCount(session, template("trap_drill")) > 0)
    }

    @Test
    fun everyGenreTemplateCanBeAppliedToTheDefaultSessionWithoutLosingTracks() {
        val session = defaultSession()
        for (template in MixReferenceTemplateLibrary.ALL) {
            val after = TemplateApplier.applyToSession(session, template)
            assertEquals("${template.id} must not add or drop tracks", 16, after.tracks.size)
            assertNotNull(after.masterPluginChain)
        }
    }
}
