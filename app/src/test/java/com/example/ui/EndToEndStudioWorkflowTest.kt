package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.engine.ExportFormat
import com.example.audio.model.FadeCurve
import com.example.audio.model.MasterPreset
import com.example.audio.model.PresetLibrary
import com.example.audio.model.ReverbConfig
import com.example.audio.model.TrackPreset
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
class EndToEndStudioWorkflowTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @org.junit.After
    fun tearDown() {
        viewModel.onCleared()
    }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = DawViewModel(application)
    }

    @Test
    fun testCompleteEndToEndProductionWorkflow() {
        // The app ships with an empty session; install a take for timeline edits.
        viewModel.installTestClip(0)

        // Step 1: Verify project session initialization (16 multi-tracks)
        val session = viewModel.session.value
        assertEquals("Session should have 16 tracks", 16, session.tracks.size)
        assertFalse("Transport should start paused", viewModel.uiState.value.isPlaying)

        // Step 2: Apply pro track preset to Lead Vocal (Track 13)
        val vocalPreset = PresetLibrary.TRACK_PRESETS.first { it.name.contains("Vocal") }
        viewModel.applyTrackPreset(13, vocalPreset)
        assertEquals(vocalPreset.reverbSendLevel, session.tracks[13].reverbSendLevel, 0.001f)

        // Step 3: Apply commercial master bus preset & configure Schroeder Reverb parameters
        val masterPreset = PresetLibrary.MASTER_PRESETS.first()
        viewModel.applyMasterPreset(masterPreset)
        assertEquals(masterPreset.masterGainDb, viewModel.session.value.masterGainDb, 0.001f)

        val customReverb = ReverbConfig(
            enabled = true,
            preDelayMs = 35f,
            decayTimeSec = 4.2f,
            roomSize = 0.75f,
            returnLevel = 0.85f,
            lowCutHz = 120f,
            highCutHz = 8500f,
            stereoWidth = 0.90f
        )
        viewModel.updateMasterReverb(customReverb)
        assertEquals(35f, viewModel.session.value.masterReverb.preDelayMs, 0.001f)
        assertEquals(4.2f, viewModel.session.value.masterReverb.decayTimeSec, 0.001f)

        // Step 4: Perform timeline edits (duplicate clip, trim end, apply fades, split clip)
        val track0 = viewModel.session.value.tracks[0]
        val clip = track0.clips.first()
        viewModel.duplicateClip(0, clip.id)
        assertTrue("Track 0 should have duplicated clip", viewModel.session.value.tracks[0].clips.size > 1)

        val duplicatedClip = viewModel.session.value.tracks[0].clips.last()
        viewModel.setClipGain(0, duplicatedClip.id, 1.5f)
        viewModel.setClipFadeIn(0, duplicatedClip.id, 250f, FadeCurve.EQUAL_POWER)
        viewModel.setClipFadeOut(0, duplicatedClip.id, 200f, FadeCurve.S_CURVE)

        viewModel.seekToFraction(0.3f)
        viewModel.splitClipAtPlayhead(0)

        // Step 5: Recording workflow simulation
        viewModel.toggleTrackArm(1)
        assertTrue("Track 1 should be armed for recording", viewModel.session.value.tracks[1].isArmedForRecording)
        viewModel.startRecording()
        assertTrue("Recording state should be active", viewModel.uiState.value.isRecording)
        viewModel.stopRecording()
        assertFalse("Recording state should stop", viewModel.uiState.value.isRecording)

        // Step 6: History undo and redo verification
        assertTrue("Undo stack should be populated", viewModel.canUndo())
        viewModel.undo()
        viewModel.redo()

        // Step 7: Offline session rendering to WAV and AAC
        viewModel.renderSessionToAudio(
            format = ExportFormat.WAV_24BIT,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val renderedWav = viewModel.uiState.value.exportedFile
        assertNotNull("WAV offline render output file must not be null", renderedWav)

        viewModel.renderSessionToAudio(
            format = ExportFormat.AAC_320KBPS,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val renderedAac = viewModel.uiState.value.exportedFile
        assertNotNull("AAC offline render output file must not be null", renderedAac)

        // Step 8: AI Mix Advisor & Latency calibration check
        viewModel.refreshAudioAnalysis()
        viewModel.runLatencyCalibration()
        assertNotNull("Status message should report operations", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun testAdvancedRecordingTakeAndClipMovementWorkflow() {
        // 1. Multiple recording passes (takes) on track 2
        viewModel.toggleTrackArm(2)
        
        // Pass 1
        viewModel.startRecording()
        viewModel.stopRecording()
        val take1 = viewModel.session.value.tracks[2].clips.last()
        
        // Pass 2
        viewModel.startRecording()
        viewModel.stopRecording()
        val take2 = viewModel.session.value.tracks[2].clips.last()
        
        // 2. Select take (swap active clip to take1)
        viewModel.setClipGain(2, take1.id, 0.0f)
        viewModel.setClipGain(2, take2.id, -60.0f) // Mute take 2
        
        // 3. Move active clip to new position
        viewModel.moveClipToFrame(2, take1.id, 96000L) // Move to 2 seconds in
        val movedTake1 = viewModel.session.value.tracks[2].clips.find { it.id == take1.id }
        assertEquals(96000L, movedTake1!!.timelineStartFrame)
        
        // 4. Verify undo/redo of movement
        viewModel.undo()
        val undoneTake1 = viewModel.session.value.tracks[2].clips.find { it.id == take1.id }
        assertTrue("Clip movement should undo", undoneTake1!!.timelineStartFrame != 96000L)
    }
}

