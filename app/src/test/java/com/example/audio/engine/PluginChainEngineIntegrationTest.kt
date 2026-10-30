package com.example.audio.engine

import com.example.audio.model.BusData
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * Runs the real render loop (Robolectric AudioTrack) with studio chains loaded
 * to prove the engine actually processes them: a track chain, a group bus
 * chain and the mix bus chain all have to show gain reduction with hot audio,
 * and a track with no chain must stay on the legacy strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginChainEngineIntegrationTest {

    private val sampleRate = 48000

    private fun slot(kind: PluginKind, vararg params: Pair<String, Float>): PluginSlot {
        var s = PluginCatalog.defaultSlot(kind)
        for ((k, v) in params) s = s.withParam(k, v)
        return s
    }

    private fun hotTrack(index: Int, outputBusId: String = "master", chain: List<PluginSlot> = emptyList()): TrackData =
        TrackData(index = index, name = "Track ${index + 1}", colorHex = 0, outputBusId = outputBusId, pluginChain = chain)

    /** 4 seconds of a constant hot signal, so gain reduction has time to settle. */
    private fun hotBuffer(): FloatArray = FloatArray(sampleRate * 4 * 2) { 0.9f }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun trackChainRunsInTheEngineAndShowsPerSlotGainReduction() {
        val engine = AudioTrackPlaybackEngine(sampleRate = sampleRate, configuredBufferSizeFrames = 512)
        try {
            engine.setTrackAudio(0, hotBuffer())
            engine.setTrackAudio(1, hotBuffer())
            val session = ProjectSession(
                tracks = listOf(
                    hotTrack(0, chain = listOf(slot(PluginKind.CHANNEL_LIMITER, "ceilingDb" to -12f))),
                    hotTrack(1) // no chain: legacy strip
                )
            )
            engine.syncFromProject(session)
            engine.start()
            assertTrue(waitUntil(4000) { engine.isEngineRunning() })
            engine.commandQueue.enqueue(AudioEngineCommand.Play)
            assertTrue(waitUntil(4000) { engine.isEnginePlaying() })

            assertTrue(
                "limiters on a hot track must reduce gain in the render loop",
                waitUntil(6000) { engine.trackGainReductions[0] > 5f }
            )
            val slots = engine.trackSlotGainReductions(0)
            assertEquals("chain must expose one metered slot", 1, slots.size)
            assertTrue("slot meter must report the reduction, was ${slots[0]}", slots[0] > 5f)

            // The untouched track keeps its own (disabled) legacy compressor.
            assertTrue("legacy track must stay near zero GR", kotlin.math.abs(engine.trackGainReductions[1]) < 0.01f)
            assertEquals(0, engine.trackSlotGainReductions(1).size)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun groupBusChainProcessesTheTracksRoutedIntoIt() {
        val engine = AudioTrackPlaybackEngine(sampleRate = sampleRate, configuredBufferSizeFrames = 512)
        try {
            engine.setTrackAudio(0, hotBuffer())
            engine.setTrackAudio(1, hotBuffer())
            val buses = listOf(
                BusData(
                    id = "bus-drums",
                    name = "Drums Bus",
                    pluginChain = listOf(slot(PluginKind.CHANNEL_LIMITER, "ceilingDb" to -15f))
                )
            )
            val session = ProjectSession(
                tracks = listOf(
                    hotTrack(0, outputBusId = "bus-drums"),
                    hotTrack(1, outputBusId = "bus-drums")
                ),
                buses = buses
            )
            engine.syncFromProject(session)
            engine.start()
            assertTrue(waitUntil(4000) { engine.isEngineRunning() })
            engine.commandQueue.enqueue(AudioEngineCommand.Play)
            assertTrue(waitUntil(4000) { engine.isEnginePlaying() })

            assertTrue(
                "the drum bus chain must clamp the summed tracks",
                waitUntil(6000) { engine.busGainReductions[0] > 3f }
            )
            // With the bus compressing, the summed mix must stay under the ceiling.
            assertTrue(
                "bus output must respect the ceiling",
                waitUntil(4000) { engine.masterPeakL <= 10f.pow(-15f / 20f) + 0.02f && engine.masterPeakL > 0f }
            )
            assertTrue("legacy master limiter path stays inactive", engine.masterGainReductionDb == 0f)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun mixBusChainReplacesTheLegacyLimiterOnTheMaster() {
        val engine = AudioTrackPlaybackEngine(sampleRate = sampleRate, configuredBufferSizeFrames = 512)
        try {
            engine.setTrackAudio(0, hotBuffer())
            val session = ProjectSession(
                tracks = listOf(hotTrack(0)),
                masterPluginChain = listOf(
                    slot(PluginKind.CHANNEL_LIMITER, "ceilingDb" to -12f),
                    slot(PluginKind.TAPE_MACHINE, "driveDb" to 2f)
                )
            )
            engine.syncFromProject(session)
            engine.start()
            assertTrue(waitUntil(4000) { engine.isEngineRunning() })
            engine.commandQueue.enqueue(AudioEngineCommand.Play)
            assertTrue(waitUntil(4000) { engine.isEnginePlaying() })

            assertTrue(
                "mix bus chain must reduce gain on the master path",
                waitUntil(6000) { engine.masterGainReductionDb > 3f }
            )
            assertTrue(
                "master output must sit under the chain ceiling",
                waitUntil(4000) { engine.masterPeakL <= 10f.pow(-12f / 20f) + 0.02f && engine.masterPeakL > 0f }
            )
        } finally {
            engine.stop()
        }
    }
}
