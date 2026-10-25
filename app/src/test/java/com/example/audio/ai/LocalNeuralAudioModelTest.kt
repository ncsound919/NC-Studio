package com.example.audio.ai

import com.example.audio.model.StemCategory
import com.example.audio.model.TrackFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNeuralAudioModelTest {

    @Test
    fun testLocalModelStructureAndQuantizedWeights() {
        assertEquals(16, LocalNeuralAudioModel.NUM_INPUTS)
        assertEquals(32, LocalNeuralAudioModel.HIDDEN_NEURONS)
        assertEquals(7, LocalNeuralAudioModel.NUM_CLASSES)
        assertEquals(4, LocalNeuralAudioModel.NUM_TARGETS)
    }

    @Test
    fun testBassStemClassification() {
        val bassFeatures = TrackFeatures(
            trackIndex = 0,
            subEnergy = 0.55f,
            lowEnergy = 0.35f,
            midEnergy = 0.08f,
            highMidEnergy = 0.02f,
            airEnergy = 0.0f,
            crestFactorDb = 8.0f,
            spectralCentroidHz = 180.0f,
            peakDb = -6.0f,
            rmsDb = -14.0f
        )

        val result = LocalNeuralAudioModel.predict(bassFeatures)

        assertNotNull(result)
        assertTrue("Model must run locally", result.isLocalOffline)
        assertTrue("Top prediction should be BASS for heavy sub energy", result.topPredictedStem == StemCategory.BASS)
        assertTrue("Confidence should be above threshold", result.confidence > 0.30f)

        // Softmax validation: sum of probabilities must equal 1.0
        val sumProb = result.stemProbabilities.values.sum()
        assertEquals("Softmax probabilities must sum to 1.0", 1.0f, sumProb, 0.001f)
    }

    @Test
    fun testDrumsStemClassification() {
        val drumFeatures = TrackFeatures(
            trackIndex = 1,
            subEnergy = 0.25f,
            lowEnergy = 0.15f,
            midEnergy = 0.15f,
            highMidEnergy = 0.20f,
            airEnergy = 0.25f,
            crestFactorDb = 18.5f, // High crest factor indicates transients / drums
            spectralCentroidHz = 2400.0f,
            peakDb = -1.2f,
            rmsDb = -18.0f
        )

        val result = LocalNeuralAudioModel.predict(drumFeatures)

        assertNotNull(result)
        assertEquals(StemCategory.DRUMS, result.topPredictedStem)
        assertTrue("Transient punch score should be high for drums", result.transientPunchScore > 0.40f)
    }

    @Test
    fun testVocalStemClassification() {
        val vocalFeatures = TrackFeatures(
            trackIndex = 2,
            subEnergy = 0.02f,
            lowEnergy = 0.08f,
            midEnergy = 0.55f,
            highMidEnergy = 0.25f,
            airEnergy = 0.10f,
            crestFactorDb = 10.0f,
            spectralCentroidHz = 1800.0f,
            peakDb = -8.0f,
            rmsDb = -18.0f
        )

        val result = LocalNeuralAudioModel.predict(vocalFeatures)

        assertNotNull(result)
        assertEquals(StemCategory.VOCAL, result.topPredictedStem)
    }

    @Test
    fun testRegressionTargetBounds() {
        val testFeatures = TrackFeatures(
            trackIndex = 3,
            subEnergy = 0.20f,
            midEnergy = 0.40f,
            airEnergy = 0.20f,
            crestFactorDb = 12.0f,
            spectralCentroidHz = 1200.0f
        )

        val result = LocalNeuralAudioModel.predict(testFeatures)

        assertTrue("Target LUFS offset must be bounded [-6, +3] dB", result.targetLufsOffsetDb in -6.0f..3.0f)
        assertTrue("Suggested EQ boost must be bounded [-4, +4] dB", result.suggestedEqBoostDb in -4.0f..4.0f)
        assertTrue("Compressor ratio must be bounded [1.5, 6.0]", result.suggestedCompRatio in 1.5f..6.0f)
        assertTrue("Punch score must be in [0.0, 1.0]", result.transientPunchScore in 0.0f..1.0f)
        assertEquals(32, result.activationsLayer1.size)
    }

    @Test
    fun testInferenceLatencyBenchmark() {
        val features = TrackFeatures(trackIndex = 0, midEnergy = 0.5f, crestFactorDb = 10f)

        // Warmup
        LocalNeuralAudioModel.predict(features)

        val start = System.nanoTime()
        val iterations = 100
        for (i in 0 until iterations) {
            LocalNeuralAudioModel.predict(features)
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val avgPerInferenceMs = totalMs / iterations

        assertTrue("Local CPU inference must be ultra-fast (< 1.0 ms per run)", avgPerInferenceMs < 1.0)
    }
}
