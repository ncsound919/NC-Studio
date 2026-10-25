package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.model.BufferSizePreset
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DawSettings
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.FilterType
import com.example.audio.model.MeterBallistics
import com.example.audio.model.SaturationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DawViewModelRobolectricTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = DawViewModel(application)
    }

    @Test
    fun initialization_defaultTracksAndSettings() {
        val session = viewModel.session.value
        assertEquals("Initial project must have 16 tracks", 16, session.tracks.size)
        assertEquals("Kick Drum", session.tracks[0].name)
        assertEquals("Snare & Clap", session.tracks[1].name)
        assertEquals("Lead Vocal", session.tracks[13].name)

        val settings = viewModel.settings.value
        assertEquals(48000, settings.sampleRateHz)
        assertEquals(BufferSizePreset.BALANCED, settings.bufferSize)

        val uiState = viewModel.uiState.value
        assertEquals(0, uiState.selectedTrackIndex)
        assertFalse(uiState.isPlaying)
        assertFalse(uiState.isRecording)
        assertFalse(viewModel.canUndo())
        assertFalse(viewModel.canRedo())
    }

    @Test
    fun transportControls_playPauseStopLoop() {
        assertFalse(viewModel.uiState.value.isPlaying)

        viewModel.togglePlayPause()
        assertTrue(viewModel.uiState.value.isPlaying)

        viewModel.togglePlayPause()
        assertFalse(viewModel.uiState.value.isPlaying)

        viewModel.seekToFraction(0.5f)
        viewModel.stop()
        assertEquals(0L, viewModel.uiState.value.playheadFrame)

        val initialLoop = viewModel.session.value.loopEnabled
        viewModel.toggleLoop()
        assertEquals(!initialLoop, viewModel.session.value.loopEnabled)
    }

    @Test
    fun mixerControls_gainPanSoloMuteArm() {
        // Gain
        viewModel.setTrackGain(0, -6.5f)
        assertEquals(-6.5f, viewModel.session.value.tracks[0].gainDb, 0.001f)

        // Pan
        viewModel.setTrackPan(1, 0.45f)
        assertEquals(0.45f, viewModel.session.value.tracks[1].pan, 0.001f)

        // Solo
        assertFalse(viewModel.session.value.tracks[2].isSoloed)
        viewModel.toggleTrackSolo(2)
        assertTrue(viewModel.session.value.tracks[2].isSoloed)

        // Mute
        assertFalse(viewModel.session.value.tracks[3].isMuted)
        viewModel.toggleTrackMute(3)
        assertTrue(viewModel.session.value.tracks[3].isMuted)

        // Arm for recording (exclusive)
        viewModel.toggleTrackArm(4)
        assertTrue(viewModel.session.value.tracks[4].isArmedForRecording)
        assertFalse(viewModel.session.value.tracks[0].isArmedForRecording)

        // Switch armed track to 5
        viewModel.toggleTrackArm(5)
        assertTrue(viewModel.session.value.tracks[5].isArmedForRecording)
        assertFalse(viewModel.session.value.tracks[4].isArmedForRecording)
    }

    @Test
    fun channelStrip_updatesAllDspProcessors() {
        val targetTrack = 0

        // EQ
        val newEq = EqConfig(
            enabled = true,
            band1 = EqBand(FilterType.LOW_SHELF, 80f, 4.0f, 0.707f),
            band4 = EqBand(FilterType.HIGH_SHELF, 12000f, 3.0f, 0.707f)
        )
        viewModel.updateTrackEq(targetTrack, newEq)
        assertEquals(4.0f, viewModel.session.value.tracks[targetTrack].eqConfig.band1.gainDb, 0.001f)

        // Saturation
        val newSat = SaturationConfig(enabled = true, driveDb = 9.0f, warmth = 0.8f, mix = 0.75f)
        viewModel.updateTrackSaturation(targetTrack, newSat)
        assertEquals(9.0f, viewModel.session.value.tracks[targetTrack].saturationConfig.driveDb, 0.001f)

        // Compressor
        val newComp = CompressorConfig(enabled = true, thresholdDb = -18f, ratio = 4f, attackMs = 12f)
        viewModel.updateTrackCompressor(targetTrack, newComp)
        assertEquals(-18f, viewModel.session.value.tracks[targetTrack].compressorConfig.thresholdDb, 0.001f)

        // Ducking Delay
        val newDelay = DuckingDelayConfig(enabled = true, timeMs = 250f, feedback = 0.4f, duckingEnabled = true)
        viewModel.updateTrackDuckingDelay(targetTrack, newDelay)
        assertEquals(250f, viewModel.session.value.tracks[targetTrack].delayConfig.timeMs, 0.001f)
        assertTrue(viewModel.session.value.tracks[targetTrack].delayConfig.duckingEnabled)

        // Channel Limiter
        val newLimiter = ChannelLimiterConfig(enabled = true, ceilingDb = -1.0f)
        viewModel.updateTrackLimiter(targetTrack, newLimiter)
        assertEquals(-1.0f, viewModel.session.value.tracks[targetTrack].limiterConfig.ceilingDb, 0.001f)
    }

    @Test
    fun settings_updateAndFactoryReset() {
        val customSettings = DawSettings(
            bufferSize = BufferSizePreset.ULTRA_LOW,
            sampleRateHz = 48000,
            latencyCompensationMs = 14.5f,
            meterBallistics = MeterBallistics.PEAK_HOLD
        )

        viewModel.updateSettings(customSettings)
        assertEquals(BufferSizePreset.ULTRA_LOW, viewModel.settings.value.bufferSize)
        assertEquals(14.5f, viewModel.settings.value.latencyCompensationMs, 0.001f)
        assertEquals(14.5f, viewModel.session.value.latencyOffsetMs, 0.001f)
        assertEquals(MeterBallistics.PEAK_HOLD, viewModel.settings.value.meterBallistics)

        // Reset to factory defaults
        viewModel.resetSettingsToDefaults()
        assertEquals(BufferSizePreset.BALANCED, viewModel.settings.value.bufferSize)
        assertEquals(32.5f, viewModel.settings.value.latencyCompensationMs, 0.001f)
        assertEquals(32.5f, viewModel.session.value.latencyOffsetMs, 0.001f)
    }

    @Test
    fun clipEditing_trimEndWithUndoAndRedo() {
        val track = viewModel.session.value.tracks[0]
        val clip = track.clips.firstOrNull()
        assertNotNull("Track 0 should have an initial clip", clip)

        val originalLength = clip!!.lengthFrames
        val trimAmount = 9600L // 200ms

        viewModel.trimClipEnd(0, clip.id, trimAmount)
        val trimmedLength = viewModel.session.value.tracks[0].clips.first().lengthFrames
        assertEquals(originalLength - trimAmount, trimmedLength)
        assertTrue(viewModel.canUndo())

        // Perform Undo
        viewModel.undo()
        val restoredLength = viewModel.session.value.tracks[0].clips.first().lengthFrames
        assertEquals(originalLength, restoredLength)
        assertTrue(viewModel.canRedo())

        // Perform Redo
        viewModel.redo()
        val redoneLength = viewModel.session.value.tracks[0].clips.first().lengthFrames
        assertEquals(originalLength - trimAmount, redoneLength)
    }

    @Test
    fun uiDialogToggles() {
        viewModel.showSettingsDialog(true)
        assertTrue(viewModel.uiState.value.showSettingsDialog)
        viewModel.showSettingsDialog(false)
        assertFalse(viewModel.uiState.value.showSettingsDialog)

        viewModel.showUsbDialog(true)
        assertTrue(viewModel.uiState.value.showUsbDialog)
        viewModel.showUsbDialog(false)
        assertFalse(viewModel.uiState.value.showUsbDialog)

        viewModel.showImportDialog(true)
        assertTrue(viewModel.uiState.value.showImportDialog)
        viewModel.showImportDialog(false)
        assertFalse(viewModel.uiState.value.showImportDialog)
    }

    @Test
    fun smallModelAutoDetectionAndSelection() {
        // Trigger local model scanning with Unconfined dispatcher for synchronous execution in test
        viewModel.scanForLocalModels(dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

        val state = viewModel.uiState.value.localModelDetectorState
        assertFalse(state.isScanning)
        assertTrue(state.detectedModels.isNotEmpty())

        val firstModel = state.detectedModels.first()
        viewModel.selectLocalModel(firstModel)

        assertEquals(firstModel.name, viewModel.settings.value.activeLocalModelName)
        assertEquals(firstModel.name, viewModel.uiState.value.localModelDetectorState.activeModel?.name)

        // Test sending prompt to selected small model
        viewModel.sendPromptToLocalModel("Recommend EQ settings for kick drum", dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val response = viewModel.uiState.value.localModelDetectorState.lastInferenceResponse
        assertNotNull(response)
    }

    @Test
    fun clipBinAndDuplicationOperations() {
        val track = viewModel.session.value.tracks[0]
        val initialClip = track.clips.first()
        val initialCount = track.clips.size

        // Test clip bin dialog toggle
        viewModel.showClipBin(true)
        assertTrue(viewModel.uiState.value.showClipBin)
        viewModel.showClipBin(false)
        assertFalse(viewModel.uiState.value.showClipBin)

        // Test duplicate clip (Pro Tools duplicate command)
        viewModel.duplicateClip(0, initialClip.id)
        val updatedTrack = viewModel.session.value.tracks[0]
        assertEquals(initialCount + 1, updatedTrack.clips.size)

        // Test rename clip
        val copiedClip = updatedTrack.clips.last()
        viewModel.renameClip(0, copiedClip.id, "Custom Take Rename")
        assertEquals("Custom Take Rename", viewModel.session.value.tracks[0].clips.find { it.id == copiedClip.id }?.name)
    }

    @Test
    fun magneticGridSnappingOperations() {
        // Toggle magnetic snapping
        val initialSnap = viewModel.settings.value.isMagneticSnapEnabled
        viewModel.toggleMagneticSnap()
        assertEquals(!initialSnap, viewModel.settings.value.isMagneticSnapEnabled)
        viewModel.toggleMagneticSnap()
        assertEquals(initialSnap, viewModel.settings.value.isMagneticSnapEnabled)

        // Cycle grid resolutions
        val initialRes = viewModel.settings.value.gridResolution
        viewModel.cycleGridResolution()
        assertNotNull(viewModel.settings.value.gridResolution)

        // Nudge clip with magnetic snapping
        val track = viewModel.session.value.tracks[0]
        val clip = track.clips.first()
        val originalStart = clip.timelineStartFrame

        viewModel.nudgeSelectedClip(0, clip.id, forward = true)
        val nudgedClip = viewModel.session.value.tracks[0].clips.first { it.id == clip.id }
        assertTrue("Clip should move forward", nudgedClip.timelineStartFrame >= originalStart)
    }
}
