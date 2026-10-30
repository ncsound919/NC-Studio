package com.example.ui

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.audio.service.AudioEngineService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The foreground service and audio-focus handling hang off the transport, so
 * starting/stopping playback must start/tear it down with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransportLifecycleTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        viewModel = DawViewModel(application)
        // Drop anything the session restore started before the test's own actions.
        shadowOf(application).nextStartedService
    }

    @After
    fun tearDown() {
        viewModel.onCleared()
    }

    @Test
    fun playbackPromotesPlaybackServiceAndPauseTearsItDown() {
        viewModel.togglePlayPause()
        assertTrue("Transport must report playing", viewModel.uiState.value.isPlaying)

        val started = shadowOf(application).nextStartedService
        assertNotNull("Playing must start the playback foreground service", started)
        assertEquals(AudioEngineService::class.java.name, started?.component?.className)
        assertEquals(
            viewModel.session.value.projectName,
            started?.getStringExtra("PROJECT_NAME")
        )

        viewModel.togglePlayPause()
        assertFalse("Transport must report paused", viewModel.uiState.value.isPlaying)
        assertNull(
            "A paused transport must not leave the playback service running",
            shadowOf(application).nextStartedService
        )
    }

    @Test
    fun unpluggingHeadphonesPausesPlayback() {
        viewModel.togglePlayPause()
        assertTrue(viewModel.uiState.value.isPlaying)

        // Robolectric records broadcasts without dispatching them to registered
        // receivers, so hand the intent to the registered receiver directly.
        val noisy = Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        val receivers = shadowOf(application).getReceiversForIntent(noisy)
        assertTrue("A headset-disconnect receiver must be registered", receivers.isNotEmpty())
        receivers.forEach { it.onReceive(application, noisy) }

        assertFalse(
            "Unplugging headphones must pause playback instead of blasting the speaker",
            viewModel.uiState.value.isPlaying
        )
    }

    @Test
    fun stoppingPlaybackResetsTransportAndStopsService() {
        viewModel.togglePlayPause()
        shadowOf(application).nextStartedService

        viewModel.stop()

        assertFalse(viewModel.uiState.value.isPlaying)
        assertEquals(0L, viewModel.uiState.value.playheadFrame)
        assertNull(
            "Stopping must tear the playback service down",
            shadowOf(application).nextStartedService
        )
    }
}
