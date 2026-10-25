package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DawUiFunctionalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun functionalTest_transportButtons() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())

        composeTestRule.setContent {
            TransportBar(
                session = viewModel.session.value,
                uiState = viewModel.uiState.value,
                canUndo = viewModel.canUndo(),
                canRedo = viewModel.canRedo(),
                onTogglePlay = { viewModel.togglePlayPause() },
                onStop = { viewModel.stop() },
                onToggleRecord = { viewModel.startRecording() },
                onToggleLoop = { viewModel.toggleLoop() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onCalibrateLatency = { viewModel.runLatencyCalibration() }
            )
        }

        // Test Transport Buttons
        composeTestRule.onNodeWithTag("transport_bar").assertIsDisplayed()
        
        // Play / Pause Toggle
        composeTestRule.onNodeWithTag("transport_play_pause_button").performClick()
        assertTrue(viewModel.uiState.value.isPlaying)
        
        // Record
        composeTestRule.onNodeWithTag("transport_record_button").performClick()
        // Note: Needs track armed, but just testing UI interaction
        
        // Undo / Redo
        composeTestRule.onNodeWithTag("undo_button").performClick()
        composeTestRule.onNodeWithTag("redo_button").performClick()
    }
}
