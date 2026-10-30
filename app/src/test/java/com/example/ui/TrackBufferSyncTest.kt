package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.audio.engine.WavAudioIO
import com.example.audio.model.AutoPitchConfig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackBufferSyncTest {

    @Test
    fun movingAClipAndUndoingItUpdatesThePlayableBuffer() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DawViewModel(application)
        viewModel.installTestClip(0, withAudio = true)

        try {
            waitUntil(20_000) {
                viewModel.trackAudioBuffers[0].isNotEmpty() &&
                    viewModel.trackAudioBuffers[0].any { it != 0f }
            }

            val probeIndex = viewModel.trackAudioBuffers[0].indexOfFirst { kotlin.math.abs(it) > 1e-4f }
            assertTrue("initial buffer should contain audio", probeIndex >= 0)

            val clip = viewModel.session.value.tracks[0].clips.first()
            // 48 000 frames = two beats at 120 BPM: Grid mode drops the clip on
            // that grid line, so this lands exactly where it is asked to.
            viewModel.moveClipToFrame(0, clip.id, 48_000L)

            // The only clip now starts at 48 000 frames, so the probe becomes silent.
            waitUntil(10_000) { kotlin.math.abs(viewModel.trackAudioBuffers[0][probeIndex]) <= 1e-4f }
            assertEquals(48_000L, viewModel.session.value.tracks[0].clips.first().timelineStartFrame)

            viewModel.undo()

            // Undo must restore the audible buffer, not just the model.
            assertEquals(0L, viewModel.session.value.tracks[0].clips.first().timelineStartFrame)
            waitUntil(10_000) { kotlin.math.abs(viewModel.trackAudioBuffers[0][probeIndex]) > 1e-4f }
            assertTrue(kotlin.math.abs(viewModel.trackAudioBuffers[0][probeIndex]) > 1e-4f)
        } finally {
            viewModel.viewModelScope.cancel()
            viewModel.onCleared()
        }
    }

    @Test
    fun importedWavSurvivesAClipRebuild() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DawViewModel(application)
        viewModel.installTestClip(3, withAudio = true)

        try {
            waitUntil(20_000) { viewModel.trackAudioBuffers[3].any { it != 0f } }

            val wav = java.io.File(application.cacheDir, "imported-test.wav")
            val frames = 24000
            val tone = FloatArray(frames * 2) { i ->
                val t = (i / 2).toDouble() / 48000.0
                (kotlin.math.sin(2.0 * Math.PI * 450.0 * t) * 0.3).toFloat()
            }
            WavAudioIO.writeWavFile(wav, tone, channels = 2, sampleRate = 48000, bitsPerSample = 16)

            viewModel.importAudioFile(Uri.fromFile(wav), 3)
            waitUntil(10_000) {
                val sourcePath = viewModel.session.value.tracks[3].clips.firstOrNull()?.sourcePath
                sourcePath != null && sourcePath != "built_in" && sourcePath.endsWith(".wav")
            }
            val clip = viewModel.session.value.tracks[3].clips.first()
            assertTrue("imported clip should reference a persisted WAV", clip.sourcePath.endsWith(".wav"))
            val probeIndex = viewModel.trackAudioBuffers[3].indexOfFirst { kotlin.math.abs(it) > 1e-4f }
            assertTrue("imported audio should be audible", probeIndex >= 0)

            // Enabling AutoPitch must re-render the playable buffer (450 Hz is
            // deliberately off-pitch, so the corrected buffer has to change).
            val beforePitch = viewModel.trackAudioBuffers[3].copyOf()
            viewModel.updateTrackAutoPitch(3, AutoPitchConfig(enabled = true, correctionStrength = 1f))
            waitUntil(10_000) {
                val after = viewModel.trackAudioBuffers[3]
                after.isNotEmpty() &&
                    (after.size != beforePitch.size ||
                        after.indices.any { kotlin.math.abs(after[it] - beforePitch[it]) > 1e-4f })
            }

            viewModel.moveClipToFrame(3, clip.id, 100_000L)
            waitUntil(10_000) {
                val movedProbe = viewModel.trackAudioBuffers[3]
                movedProbe.size > probeIndex && kotlin.math.abs(movedProbe[probeIndex]) <= 1e-4f
            }
        } finally {
            viewModel.viewModelScope.cancel()
            viewModel.onCleared()
        }
    }

    @Test
    fun blankSessionNeverAllocatesSongLengthTrackBuffers() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DawViewModel(application)
        try {
            viewModel.awaitSessionReady()
            assertTrue(viewModel.session.value.tracks.all { it.clips.isEmpty() })
            // The session default is a 16-second project; sizing every lane to
            // that (or worse, to a full song) is the heap regression this guards.
            viewModel.trackAudioBuffers.forEachIndexed { index, buffer ->
                assertTrue("empty track $index must not reserve a buffer", buffer.isEmpty())
            }

            viewModel.installTestClip(0)
            val clipEnd = viewModel.session.value.tracks[0].clips.first().lengthFrames
            assertTrue(
                "a populated track is sized to its own content, not the session length",
                viewModel.trackAudioBuffers[0].size == clipEnd.toInt() * 2,
            )
            viewModel.trackAudioBuffers.drop(1).forEachIndexed { index, buffer ->
                assertTrue("track ${index + 1} stays empty", buffer.isEmpty())
            }
        } finally {
            viewModel.viewModelScope.cancel()
            viewModel.onCleared()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }
}
