package com.example.audio.ai

import com.example.audio.model.CompressorType
import com.example.audio.model.EqBand
import com.example.audio.model.FilterType
import com.example.audio.model.ProjectSession
import com.example.audio.model.SaturationType
import com.example.audio.model.StemCategory
import com.example.audio.model.TrackData
import com.example.audio.model.TrackFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MixAdvisorAndAiAssistantTest {

    private val sampleRate = 48000

    @Test
    fun extractFeatures_zeroSignalProducesMinimalEnergy() {
        val emptySamples = FloatArray(48000) { 0f }
        val features = AudioFeatureExtractor.extractFeatures(0, emptySamples, sampleRate)

        assertEquals(0, features.trackIndex)
        assertEquals(-80f, features.peakDb, 0.1f)
        assertEquals(-80f, features.rmsDb, 0.1f)
        assertEquals(StemCategory.OTHER, features.classifiedStem)
    }

    @Test
    fun extractFeatures_lowFrequencySineClassifiesAsBass() {
        // Generate 60 Hz sub-bass sine wave
        val bassSamples = FloatArray(48000) { i ->
            0.6f * sin(2.0 * Math.PI * 60.0 * i / sampleRate).toFloat()
        }

        val features = AudioFeatureExtractor.extractFeatures(1, bassSamples, sampleRate)

        assertEquals(1, features.trackIndex)
        assertTrue("Sub energy fraction should be high for 60Hz", features.subEnergy > 0.3f)
        assertEquals("Should classify 60Hz sine as BASS stem", StemCategory.BASS, features.classifiedStem)
    }

    @Test
    fun detectMaskingClashes_detectsLowEndCollisionBetweenKickAndBass() {
        val track0 = TrackData(index = 0, name = "Kick Drum", colorHex = 0xFFFF5722, stemCategory = StemCategory.DRUMS)
        val track4 = TrackData(index = 4, name = "808 Sub Bass", colorHex = 0xFF4CAF50, stemCategory = StemCategory.BASS)

        val feat0 = TrackFeatures(trackIndex = 0, subEnergy = 0.40f, lowEnergy = 0.30f)
        val feat4 = TrackFeatures(trackIndex = 4, subEnergy = 0.45f, lowEnergy = 0.35f)

        val clashes = MixAdvisor.detectMaskingClashes(
            tracks = listOf(track0, track4),
            features = listOf(feat0, feat4)
        )

        assertTrue("Should detect low-end sub collision", clashes.isNotEmpty())
        val clash = clashes.first()
        assertEquals(80f, clash.conflictFrequencyHz, 0.1f)
        assertTrue(clash.severity > 0.3f)
    }

    @Test
    fun generateSuggestions_recommendsGainReductionForClippingTrack() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 0, name = "Clipping Vocal", colorHex = 0xFFF44336, gainDb = 0f, stemCategory = StemCategory.VOCAL)
            )
        )

        val features = listOf(
            TrackFeatures(trackIndex = 0, peakDb = +0.5f, rmsDb = -10.0f)
        )

        val suggestions = MixAdvisor.generateSuggestions(session, features)

        assertTrue("Should produce gain staging suggestion for clipping track", suggestions.isNotEmpty())
        val sug = suggestions.first()
        assertEquals("Gain Staging", sug.category)
        assertTrue(sug.proposedValue.contains("dB"))
    }

    @Test
    fun parseNaturalLanguageCommand_brightenVocals() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val suggestions = MixAdvisor.parseNaturalLanguageCommand("make vocals bright and crisp", session)

        assertTrue("Should produce tone shaping suggestion for bright vocals", suggestions.isNotEmpty())
        val sug = suggestions.first()
        assertEquals("Tone Shaping", sug.category)
        assertEquals(13, sug.trackIndex)
    }

    @Test
    fun parseNaturalLanguageCommand_glueDrums() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 0, name = "Kick Drum", colorHex = 0xFFFF5722, stemCategory = StemCategory.DRUMS)
            )
        )

        val suggestions = MixAdvisor.parseNaturalLanguageCommand("glue the drums together", session)

        assertTrue("Should produce dynamic glue suggestion for drums", suggestions.isNotEmpty())
        val sug = suggestions.first()
        assertEquals("Dynamic Glue", sug.category)
        assertEquals(0, sug.trackIndex)
    }

    @Test
    fun applySuggestionsToSession_modifiesTrackEqAndGainAtomically() {
        val session = ProjectSession(
            revision = 1L,
            tracks = listOf(
                TrackData(index = 0, name = "Track 0", colorHex = 0xFFFF5722, gainDb = 0f)
            )
        )

        val suggestions = listOf(
            com.example.audio.model.MixSuggestion(
                trackIndex = 0,
                trackName = "Track 0",
                category = "Gain Staging",
                parameter = "Fader Gain",
                currentValue = "0.0 dB",
                proposedValue = "-4.5 dB",
                reason = "Lowering fader",
                confidence = 0.9f,
                targetRevision = 1L
            )
        )

        val updatedSession = MixAdvisor.applySuggestionsToSession(session, suggestions)

        assertEquals(2L, updatedSession.revision)
        assertEquals(-4.5f, updatedSession.tracks[0].gainDb, 0.01f)
    }

    @Test
    fun toolCallParsing_acceptsFlatNestedNumbersAndBooleans() {
        val raw = """
            {"tool":"setCompressor","args":{"trackIndex": 13, "type":"FET_1176","enabled": true}}
            {"tool":"setEqFilter","trackIndex":"13","filter":"highPass","frequencyHz":"90"}
        """.trimIndent()

        val calls = ToolCallDispatcher.parseToolCalls(raw)

        assertEquals(2, calls.size)
        assertEquals("setCompressor", calls[0].name)
        assertEquals("13", calls[0].args["trackIndex"])
        assertEquals("true", calls[0].args["enabled"])
        assertEquals("90", calls[1].args["frequencyHz"])
    }

    @Test
    fun toolCallDispatcher_appliesCircuitModelsFiltersRiderAndResonance() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )
        val calls = listOf(
            ToolCallDispatcher.ToolCall("setCompressor", mapOf(
                "trackIndex" to "13", "type" to "FET_1176", "enabled" to "true", "ratio" to "8"
            )),
            ToolCallDispatcher.ToolCall("setSaturation", mapOf(
                "trackIndex" to "13", "type" to "TUBE", "enabled" to "true", "driveDb" to "4"
            )),
            ToolCallDispatcher.ToolCall("setEqFilter", mapOf(
                "trackIndex" to "13", "filter" to "lowPass", "frequencyHz" to "14000", "enabled" to "true"
            )),
            ToolCallDispatcher.ToolCall("setVocalRider", mapOf(
                "trackIndex" to "13", "enabled" to "true", "targetLufs" to "-17"
            )),
            ToolCallDispatcher.ToolCall("setResonanceSmoother", mapOf(
                "trackIndex" to "13", "enabled" to "true", "frequencyHz" to "3400"
            ))
        )

        val updated = ToolCallDispatcher.executeAll(session, calls).tracks[0]

        assertEquals(CompressorType.FET_1176, updated.compressorConfig.type)
        assertEquals(8f, updated.compressorConfig.ratio, 0.001f)
        assertEquals(SaturationType.TUBE, updated.saturationConfig.type)
        assertTrue(updated.eqConfig.lowPass.enabled)
        assertEquals(14000f, updated.eqConfig.lowPass.frequencyHz, 0.001f)
        assertTrue(updated.vocalRiderConfig.enabled)
        assertEquals(-17f, updated.vocalRiderConfig.targetLufs, 0.001f)
        assertTrue(updated.resonanceSmootherConfig.enabled)
        assertEquals(3400f, updated.resonanceSmootherConfig.frequencyHz, 0.001f)
    }

    @Test
    fun mixEngineerBrain_vocalChainShortcutTargetsLeadVocal() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 0, name = "Kick Drum", colorHex = 0xFFFF5722, stemCategory = StemCategory.DRUMS),
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val plan = MixEngineerBrain.plan("Set up the lead vocal", session, selectedTrackIndex = 0)

        assertTrue(plan.calls.any { it.name == "applyVocalChain" && it.args["trackIndex"] == "13" })
        assertTrue(plan.summary.contains("vocal chain", ignoreCase = true))
    }

    @Test
    fun mixEngineerBrain_selectsCompressorCircuitOnDrums() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 0, name = "Kick Drum", colorHex = 0xFFFF5722, stemCategory = StemCategory.DRUMS),
                TrackData(index = 1, name = "Snare & Clap", colorHex = 0xFFFF9800, stemCategory = StemCategory.DRUMS),
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val plan = MixEngineerBrain.plan("1176 on the drums", session)
        val compCalls = plan.calls.filter { it.name == "setCompressor" }

        assertEquals(2, compCalls.size)
        assertTrue(compCalls.all { it.args["type"] == CompressorType.FET_1176.name })
        assertTrue(compCalls.none { it.args["trackIndex"] == "13" })
    }

    @Test
    fun mixEngineerBrain_tubeSaturationDoesNotImplyTubeCompressor() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val plan = MixEngineerBrain.plan("add tube saturation to the vocal", session)

        assertTrue(plan.calls.any { it.name == "setSaturation" && it.args["type"] == SaturationType.TUBE.name })
        assertTrue("Saturation must not silently add a compressor", plan.calls.none { it.name == "setCompressor" })
    }

    @Test
    fun mixEngineerBrain_understandsFiltersRiderResonanceAndTransport() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 6, name = "Rhythm Guitar L", colorHex = 0xFF00BCD4, stemCategory = StemCategory.GUITAR),
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val filterPlan = MixEngineerBrain.plan("high-pass the guitars at 100 Hz", session)
        assertTrue(filterPlan.calls.any {
            it.name == "setEqFilter" && it.args["filter"] == "highPass" && it.args["frequencyHz"] == "100.0"
        })

        val chainPlan = MixEngineerBrain.plan("vocal rider and resonance on the vocal", session)
        assertTrue(chainPlan.calls.any { it.name == "setVocalRider" && it.args["trackIndex"] == "13" })
        assertTrue(chainPlan.calls.any { it.name == "setResonanceSmoother" && it.args["trackIndex"] == "13" })

        val recordPlan = MixEngineerBrain.plan("start recording", session)
        assertTrue(recordPlan.calls.any { it.name == "transport" && it.args["action"] == "record" })
        assertTrue(
            "Record must arm the selected track when nothing is armed",
            recordPlan.calls.any { it.name == "armTrack" && it.args["trackIndex"] == "6" }
        )
    }

    @Test
    fun mixEngineerBrain_toneRequestsBecomeAppliedEqMoves() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val bright = MixEngineerBrain.plan("make the vocal brighter", session)
        assertTrue(bright.calls.any {
            it.name == "setEqBand" && it.args["band"] == "4" && it.args["gainDb"] == "2.5"
        })

        val mud = MixEngineerBrain.plan("clean up the muddy vocal", session)
        assertTrue(mud.calls.any {
            it.name == "setEqBand" && it.args["band"] == "2" && it.args["gainDb"] == "-2.5"
        })
    }

    @Test
    fun generateSuggestions_ignoresEmptyTracks() {
        val session = ProjectSession(
            tracks = listOf(TrackData(index = 0, name = "Empty Track", colorHex = 0xFFFF5722))
        )

        val suggestions = MixAdvisor.generateSuggestions(session, listOf(TrackFeatures(trackIndex = 0)))

        assertTrue("Empty tracks must not receive fader or compressor suggestions", suggestions.isEmpty())
    }

    @Test
    fun toolCallDispatcher_appliesNewPluginControls() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )
        val calls = listOf(
            ToolCallDispatcher.ToolCall("setChorus", mapOf("trackIndex" to "13", "enabled" to "true", "rateHz" to "2.0", "mix" to "0.5")),
            ToolCallDispatcher.ToolCall("setDelay", mapOf("trackIndex" to "13", "enabled" to "true", "timeMs" to "250", "feedback" to "0.4")),
            ToolCallDispatcher.ToolCall("setLimiter", mapOf("trackIndex" to "13", "enabled" to "true", "ceilingDb" to "-1.0")),
            ToolCallDispatcher.ToolCall("setAutoPitch", mapOf("trackIndex" to "13", "enabled" to "true", "correctionStrength" to "0.8")),
            ToolCallDispatcher.ToolCall("setNoiseReduction", mapOf("trackIndex" to "13", "enabled" to "true", "thresholdDb" to "-35", "reductionDb" to "-10")),
            ToolCallDispatcher.ToolCall("setSidechain", mapOf("trackIndex" to "13", "sourceTrackIndex" to "0")),
            ToolCallDispatcher.ToolCall("setTrackSend", mapOf("trackIndex" to "13", "bus" to "reverb", "level" to "0.6")),
            ToolCallDispatcher.ToolCall("setMasterLimiter", mapOf("enabled" to "true", "ceilingDb" to "-0.1")),
            ToolCallDispatcher.ToolCall("setMasterReverb", mapOf("enabled" to "true", "decayTimeSec" to "3.0", "roomSize" to "0.7"))
        )

        val updated = ToolCallDispatcher.executeAll(session, calls)
        val track = updated.tracks[0]

        assertTrue(track.chorusConfig.enabled)
        assertEquals(2.0f, track.chorusConfig.rateHz, 0.001f)
        assertEquals(0.5f, track.chorusConfig.mix, 0.001f)
        assertTrue(track.delayConfig.enabled)
        assertEquals(250f, track.delayConfig.timeMs, 0.001f)
        assertEquals(0.4f, track.delayConfig.feedback, 0.001f)
        assertTrue(track.limiterConfig.enabled)
        assertEquals(-1.0f, track.limiterConfig.ceilingDb, 0.001f)
        assertTrue(track.autoPitchConfig.enabled)
        assertEquals(0.8f, track.autoPitchConfig.correctionStrength, 0.001f)
        assertTrue(track.noiseReductionConfig.enabled)
        assertEquals(-35f, track.noiseReductionConfig.thresholdDb, 0.001f)
        assertEquals(-10f, track.noiseReductionConfig.reductionDb, 0.001f)
        assertEquals(0, track.sidechainSourceIndex)
        assertEquals(0.6f, track.reverbSendLevel, 0.001f)
        assertEquals(0.6f, track.sends["reverb"]!!, 0.001f)
        assertTrue(updated.masterLimiter.enabled)
        assertEquals(-0.1f, updated.masterLimiter.ceilingDb, 0.001f)
        assertTrue(updated.masterReverb.enabled)
        assertEquals(3.0f, updated.masterReverb.decayTimeSec, 0.001f)
        assertEquals(0.7f, updated.masterReverb.roomSize, 0.001f)
    }

    @Test
    fun mixEngineerBrain_newPluginKeywordsProduceToolCalls() {
        val session = ProjectSession(
            tracks = listOf(
                TrackData(index = 0, name = "Kick Drum", colorHex = 0xFFFF5722, stemCategory = StemCategory.DRUMS),
                TrackData(index = 13, name = "Lead Vocal", colorHex = 0xFFF44336, stemCategory = StemCategory.VOCAL)
            )
        )

        val chorus = MixEngineerBrain.plan("add chorus to the vocal", session)
        assertTrue(chorus.calls.any { it.name == "setChorus" && it.args["trackIndex"] == "13" })

        val delay = MixEngineerBrain.plan("slapback delay on the vocal", session)
        assertTrue(delay.calls.any { it.name == "setDelay" && it.args["trackIndex"] == "13" })

        val limiter = MixEngineerBrain.plan("limiter on the drums", session)
        assertTrue(limiter.calls.any { it.name == "setLimiter" && it.args["trackIndex"] == "0" })

        val pitch = MixEngineerBrain.plan("autotune the vocal", session)
        assertTrue(pitch.calls.any { it.name == "setAutoPitch" && it.args["trackIndex"] == "13" })

        val nr = MixEngineerBrain.plan("noise reduction on the vocal", session)
        assertTrue(nr.calls.any { it.name == "setNoiseReduction" && it.args["trackIndex"] == "13" })

        val sc = MixEngineerBrain.plan("sidechain to track 1 on the vocal", session)
        assertTrue(sc.calls.any { it.name == "setSidechain" })

        val send = MixEngineerBrain.plan("send to reverb at 50 on the vocal", session)
        assertTrue(send.calls.any { it.name == "setTrackSend" && it.args["trackIndex"] == "13" })

        val ml = MixEngineerBrain.plan("master limiter", session)
        assertTrue(ml.calls.any { it.name == "setMasterLimiter" })

        val mr = MixEngineerBrain.plan("master reverb decay 4 s", session)
        assertTrue(mr.calls.any { it.name == "setMasterReverb" })
    }

    @Test
    fun toolCallParsing_recognizesNewToolNames() {
        val raw = """{"tool":"setChorus","trackIndex":"1","enabled":"true","mix":"0.4"}"""
        val calls = ToolCallDispatcher.parseToolCalls(raw)
        assertEquals(1, calls.size)
        assertTrue(ToolCallDispatcher.isKnownTool(calls[0].name))
    }
}
