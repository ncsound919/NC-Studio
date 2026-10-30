package com.example.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the render-loop ownership rules: the thread that writes
 * to the AudioTrack is the only one allowed to replace or release it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioEngineTransportTest {

    @Test
    fun bufferSizeChangeKeepsEngineRunningAndPlaying() {
        val engine = AudioTrackPlaybackEngine(sampleRate = 48000, configuredBufferSizeFrames = 512)
        try {
            engine.start()
            assertTrue("Render thread must come up", waitUntil(4000) { engine.isEngineRunning() })

            engine.commandQueue.enqueue(AudioEngineCommand.Play)
            assertTrue("Playhead must advance once playing", waitUntil(4000) { engine.getPlayheadPosition() > 0L })
            engine.commandQueue.enqueue(AudioEngineCommand.SetBufferSize(128))

            // The old code released the AudioTrack from the caller's thread while
            // the render loop kept writing to it, which killed audio for the rest
            // of the session. The render loop must apply the change itself.
            assertTrue(
                "Render loop must apply the new buffer size",
                waitUntil(4000) { engine.configuredBufferSizeFrames == 128 }
            )
            assertEquals(128, engine.configuredBufferSizeFrames)
            assertTrue("Engine must still run after a buffer-size change", engine.isEngineRunning())
            assertTrue("Playback must continue after a buffer-size change", engine.isEnginePlaying())

            val afterChange = engine.getPlayheadPosition()
            assertTrue(
                "Playhead must keep moving after the swap",
                waitUntil(4000) { engine.getPlayheadPosition() != afterChange }
            )
        } finally {
            engine.stop()
        }
    }

    @Test
    fun stopMakesTheEngineRestartable() {
        val engine = AudioTrackPlaybackEngine(sampleRate = 48000, configuredBufferSizeFrames = 256)
        try {
            engine.start()
            assertTrue(waitUntil(4000) { engine.isEngineRunning() })
            engine.stop()
            assertTrue("stop() must clear the running flag", !engine.isEngineRunning())

            engine.start()
            assertTrue("A stopped engine must be startable again", waitUntil(4000) { engine.isEngineRunning() })
        } finally {
            engine.stop()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
