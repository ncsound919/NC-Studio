package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.collectAsState
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.audio.model.AudioTelemetry
import com.example.audio.model.DawSettings
import com.example.ui.theme.MyApplicationTheme
import java.io.File

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

    @Test
    fun functionalTest_transportPunchStatusAndDismiss() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())

        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsState()
            TransportBar(
                session = viewModel.session.value,
                uiState = uiState.value,
                canUndo = viewModel.canUndo(),
                canRedo = viewModel.canRedo(),
                onTogglePlay = { viewModel.togglePlayPause() },
                onStop = { viewModel.stop() },
                onToggleRecord = { viewModel.startRecording() },
                onToggleLoop = { viewModel.toggleLoop() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onCalibrateLatency = { viewModel.runLatencyCalibration() },
                onSetPunchIn = { viewModel.setPunchIn() },
                onSetPunchOut = { viewModel.setPunchOut() },
                onClearPunch = { viewModel.clearPunchPoints() },
                onToggleAb = { viewModel.toggleAbComparison() },
                onMatchToReference = { viewModel.matchToReference() },
                onDismissStatus = { viewModel.clearStatusMessage() },
                hasReferenceTrack = viewModel.uiState.value.hasReferenceTrack
            )
        }

        composeTestRule.onNodeWithTag("ab_compare_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("punch_in_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertNotNull(viewModel.uiState.value.punchInFrame)
        composeTestRule.onNodeWithTag("status_banner").assertIsDisplayed()

        composeTestRule.onNodeWithTag("dismiss_status_button").performClick()
        composeTestRule.waitForIdle()

        assertNull(viewModel.uiState.value.statusMessage)
        composeTestRule.onNodeWithTag("status_banner").assertDoesNotExist()
    }

    @Test
    fun functionalTest_timelineZoomAndClipSelection() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.installTestClip(0)
        val session = viewModel.session.value
        val firstClip = session.tracks.first().clips.first()
        var toggledClip: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                TimelineView(
                    session = session,
                    uiState = viewModel.uiState.value,
                    settings = DawSettings(),
                    onSeekFraction = {},
                    onSelectTrack = {},
                    onSplitClipAtPlayhead = {},
                    onTrimClipEnd = { _, _ -> },
                    onDuplicateClip = { _, _ -> },
                    onOpenClipBin = {},
                    onToggleClipSelection = { _, id -> toggledClip = id }
                )
            }
        }

        composeTestRule.onNodeWithTag("timeline_minimap").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeline_fit_button").assertTextContains("FIT")
        composeTestRule.onNodeWithTag("timeline_zoom_in").performClick()
        composeTestRule.onNodeWithTag("timeline_fit_button").assertTextContains("x1.5")

        composeTestRule.onNodeWithTag("clip_${firstClip.id}").performClick()
        assertEquals(firstClip.id, toggledClip)
    }

    @Test
    fun functionalTest_fadeAndLoopHandlesDrag() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.installTestClip(0)
        val session = viewModel.session.value
        var fadeInMs = -1f
        var loopStart = session.loopStartFrame
        var loopEnd = session.loopEndFrame

        composeTestRule.setContent {
            MyApplicationTheme {
                TimelineView(
                    session = session,
                    uiState = viewModel.uiState.value,
                    settings = DawSettings(),
                    onSeekFraction = {},
                    onSelectTrack = {},
                    onSplitClipAtPlayhead = {},
                    onTrimClipEnd = { _, _ -> },
                    onDuplicateClip = { _, _ -> },
                    onOpenClipBin = {},
                    onSetClipFadeIn = { _, _, ms, _ -> fadeInMs = ms },
                    onSetLoopRange = { start, end ->
                        loopStart = start
                        loopEnd = end
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("fade_in_handle", useUnmergedTree = true)
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertTrue("fade-in drag should change the fade duration", fadeInMs > 0f)

        composeTestRule.onNodeWithTag("loop_start_handle", useUnmergedTree = true)
            .performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertTrue("loop start should move right", loopStart > session.loopStartFrame)
        assertTrue("loop range should remain valid", loopEnd > loopStart)
    }

    @Test
    fun functionalTest_directClipDragMovesClip() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.installTestClip(0)
        val original = viewModel.session.value.tracks.first().clips.first()
        var movedTo: Long? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                TimelineView(
                    session = viewModel.session.value,
                    uiState = viewModel.uiState.value,
                    settings = DawSettings(),
                    onSeekFraction = {},
                    onSelectTrack = {},
                    onSplitClipAtPlayhead = {},
                    onTrimClipEnd = { _, _ -> },
                    onDuplicateClip = { _, _ -> },
                    onOpenClipBin = {},
                    onMoveClip = { _, _, frame -> movedTo = frame },
                    onToggleClipSelection = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("clip_${original.id}")
            .performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(androidx.compose.ui.geometry.Offset(180f, 0f))
                up()
            }
        composeTestRule.waitForIdle()

        assertTrue(
            "Long-press drag should request a clip move to a later frame",
            (movedTo ?: -1L) > original.timelineStartFrame
        )
    }

    @Test
    fun functionalTest_clipMoveIsSnappedAndUndoable() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.installTestClip(0)
        val beforeSession = viewModel.session.value
        val track = beforeSession.tracks.first()
        val clip = track.clips.first()
        val target = clip.timelineStartFrame + 40_000L
        val expected = com.example.audio.engine.SnapEngine.snapFrame(
            targetFrame = target,
            session = beforeSession,
            isSnapEnabled = true,
            resolution = DawSettings().gridResolution
        )

        viewModel.moveClipToFrame(track.index, clip.id, rawTargetFrame = target)
        val moved = viewModel.session.value.tracks.first().clips.first { it.id == clip.id }
        assertNotEquals(clip.timelineStartFrame, moved.timelineStartFrame)
        assertEquals(expected, moved.timelineStartFrame)

        viewModel.undo()
        val restored = viewModel.session.value.tracks.first().clips.first { it.id == clip.id }
        assertEquals(clip.timelineStartFrame, restored.timelineStartFrame)
    }

    @Test
    fun functionalTest_panDetentAndExportEstimate() {
        assertEquals(0f, snapPanToCenter(0.01f), 0.0001f)
        assertEquals(0.2f, snapPanToCenter(0.2f), 0.0001f)

        val wav = formatExportSize(com.example.audio.engine.ExportFormat.WAV_24BIT, 48000, 16f)
        val mp3 = formatExportSize(com.example.audio.engine.ExportFormat.MP3_320KBPS, 48000, 16f)
        assertNotEquals(wav, mp3)
        assertEquals("12.3 MB", formatCacheSize(12_900_000L))
    }

    @Test
    fun functionalTest_presentationModeHidesEngineTelemetry() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())

        composeTestRule.setContent {
            MyApplicationTheme {
                TransportBar(
                    session = viewModel.session.value,
                    uiState = viewModel.uiState.value,
                    canUndo = false,
                    canRedo = false,
                    onTogglePlay = {},
                    onStop = {},
                    onToggleRecord = {},
                    onToggleLoop = {},
                    onUndo = {},
                    onRedo = {},
                    onCalibrateLatency = {},
                    showDiagnostics = false
                )
            }
        }

        composeTestRule.onNodeWithTag("engine_telemetry").assertDoesNotExist()
        composeTestRule.onNodeWithTag("transport_bar").assertIsDisplayed()
    }

    @Test
    fun functionalTest_exportDialogOpensAndSharesAllStems() {
        var opened: File? = null
        var sharedAll = false
        val stems = listOf(File("kick.wav"), File("snare.wav"))

        composeTestRule.setContent {
            MyApplicationTheme {
                ExportDialog(
                    progress = 1f,
                    exportedFile = stems.first(),
                    stemFiles = stems,
                    onStartExport = {},
                    onOpenFile = { opened = it },
                    onShareAllStems = { sharedAll = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("share_all_stems_button")
            .performScrollTo()
            .performClick()
        assertTrue("Share all stems should invoke its callback", sharedAll)
        assertNull(opened)
    }

    @Test
    fun functionalTest_sessionHealthCardReportsAtRisk() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        val riskyState = viewModel.uiState.value.copy(
            telemetry = AudioTelemetry(
                callbackP95Ms = 9f,
                bufferUnderruns = 2,
                sampleRate = 48000,
                bufferSizeFrames = 512
            ),
            uiFrameP95Ms = 28f
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    settings = DawSettings(),
                    uiState = riskyState,
                    onUpdateSettings = {},
                    onCalibrateLatency = {},
                    onRunDspTests = {},
                    onOpenUsbDialog = {},
                    onClearCache = {},
                    onResetDefaults = {},
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("session_health_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_health_level").assertTextContains("At risk")
    }

    @Test
    fun functionalTest_tabNavigation() {
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.dismissOnboarding()
        viewModel.dismissWhatsNew()

        composeTestRule.setContent {
            DawMainScreen(viewModel)
        }

        composeTestRule.onNodeWithTag("tab_timeline").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("timeline_view").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }
}
