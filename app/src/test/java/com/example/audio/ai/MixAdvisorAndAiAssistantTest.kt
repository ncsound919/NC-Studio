package com.example.audio.ai

import com.example.audio.model.EqBand
import com.example.audio.model.FilterType
import com.example.audio.model.ProjectSession
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
}
