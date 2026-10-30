package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.audio.engine.SnapEngine
import com.example.audio.model.AudioTelemetry
import com.example.audio.model.DawSettings
import com.example.audio.model.EditMode
import com.example.audio.model.GridResolution
import com.example.ui.theme.MyApplicationTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DawUiFunctionalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Every test builds its own ViewModel; they are torn down in @After so a
    // leaked instance cannot keep its crash-journal debounce alive and write
    // project state into the next test's restore.
    private val viewModels = mutableListOf<DawViewModel>()

    private fun newViewModel(): DawViewModel =
        DawViewModel(ApplicationProvider.getApplicationContext()).also { viewModels += it }

    @After
    fun tearDownViewModels() {
        viewModels.forEach { vm ->
            vm.viewModelScope.cancel()
            vm.onCleared()
        }
        viewModels.clear()
    }

    @Before
    fun clearPersistedSessionState() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        app.deleteFile("recovery_journal.json")
        File(app.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun functionalTest_transportButtons() {
        val viewModel = newViewModel()

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
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("transport_play_pause_button").performClick()
        composeTestRule.waitForIdle()
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
        val viewModel = newViewModel()

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
        val viewModel = newViewModel()
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
        val viewModel = newViewModel()
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
        val viewModel = newViewModel()
        viewModel.installTestClip(0)
        val original = viewModel.session.value.tracks.first().clips.first()
        var movedTo: Long? = null
        var trimmedTo: Long? = null

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
                    onMoveClip = { _, _, frame, _ -> movedTo = frame },
                    onTrimClipToFrame = { _, _, _, frame, _ -> trimmedTo = frame },
                    onToggleClipSelection = { _, _ -> }
                )
            }
        }

        // The Smart tool drags a clip body straight away: no long-press needed.
        composeTestRule.onNodeWithTag("clip_${original.id}")
            .performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(androidx.compose.ui.geometry.Offset(180f, 0f))
                up()
            }
        composeTestRule.waitForIdle()

        assertTrue(
            "A body drag should move the clip (moved=$movedTo, trimmed=$trimmedTo)",
            (movedTo ?: -1L) > original.timelineStartFrame
        )
    }

    @Test
    fun functionalTest_smartToolEdgeDragTrimsTheInPoint() {
        val viewModel = newViewModel()
        viewModel.installTestClip(0)
        val installed = viewModel.session.value.tracks.first().clips.first()
        // Pull the in point 2 s in, so there is take audio to uncover again.
        viewModel.trimClipStartToFrame(0, installed.id, 96_000L)
        val trimmedClip = viewModel.session.value.tracks.first().clips.first()
        var movedTo: Long? = null
        var trimmedTo: Long? = null
        var trimmedStartEdge: Boolean? = null

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
                    onMoveClip = { _, _, frame, _ -> movedTo = frame },
                    onTrimClipToFrame = { _, _, isStartEdge, frame, _ ->
                        trimmedStartEdge = isStartEdge
                        trimmedTo = frame
                    },
                    onToggleClipSelection = { _, _ -> }
                )
            }
        }

        // Pressing on the in point of the clip drags that edge, not the clip.
        // (Low on the lane: the fade grips own the top strip of the clip.)
        composeTestRule.onNodeWithTag("clip_${trimmedClip.id}")
            .performTouchInput {
                down(androidx.compose.ui.geometry.Offset(1f, height - 4f))
                moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f))
                up()
            }
        composeTestRule.waitForIdle()

        assertEquals(true, trimmedStartEdge)
        assertTrue(
            "Edge drag should trim the in point earlier (trimmed=$trimmedTo)",
            (trimmedTo ?: Long.MAX_VALUE) < trimmedClip.timelineStartFrame
        )
        assertNull("An edge drag must not move the whole clip", movedTo)
    }

    @Test
    fun functionalTest_spotModeDragAsksForATimeAndPlacesTheClip() {
        val viewModel = newViewModel()
        viewModel.installTestClip(0)
        val clip = viewModel.session.value.tracks.first().clips.first()

        composeTestRule.setContent {
            MyApplicationTheme {
                TimelineView(
                    session = viewModel.session.value,
                    uiState = viewModel.uiState.value,
                    settings = DawSettings(editMode = EditMode.SPOT),
                    onSeekFraction = {},
                    onSelectTrack = {},
                    onSplitClipAtPlayhead = {},
                    onTrimClipEnd = { _, _ -> },
                    onDuplicateClip = { _, _ -> },
                    onOpenClipBin = {},
                    onSpotClipAtFrame = { trackIdx, clipId, frame, placeByEnd ->
                        viewModel.spotClipAtFrame(trackIdx, clipId, frame, placeByEnd)
                    }
                )
            }
        }

        // Dropping a clip in Spot mode asks where it should go.
        composeTestRule.onNodeWithTag("clip_${clip.id}").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(60f, 0f))
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("spot_time_field").assertIsDisplayed()

        composeTestRule.onNodeWithTag("spot_time_field").performTextReplacement("2:03.5")
        composeTestRule.onNodeWithTag("spot_time_preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("spot_confirm_button").performClick()
        composeTestRule.waitForIdle()

        // 2:03.5 at 48 kHz, placed exactly: spot never snaps.
        assertEquals(
            5_928_000L,
            viewModel.session.value.tracks.first().clips.first { it.id == clip.id }.timelineStartFrame
        )
        composeTestRule.onNodeWithTag("spot_time_field").assertDoesNotExist()
    }

    @Test
    fun functionalTest_gridModeDragDoesNotAskForATime() {
        val viewModel = newViewModel()
        viewModel.installTestClip(0)
        val clip = viewModel.session.value.tracks.first().clips.first()

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
                    onOpenClipBin = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("clip_${clip.id}").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(60f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("spot_time_field").assertDoesNotExist()
    }

    @Test
    fun functionalTest_clipMoveLandsOnTheGridAndIsUndoable() {
        val viewModel = newViewModel()
        viewModel.installTestClip(0)
        val beforeSession = viewModel.session.value
        val track = beforeSession.tracks.first()
        val clip = track.clips.first()
        // Grid edit mode is a hard snap: the drop lands on the nearest grid line
        // even though the drag stopped far outside a magnetic snap window.
        val beatFrames = SnapEngine.framesPerStep(
            GridResolution.BEAT,
            beforeSession.sampleRate,
            beforeSession.tempoBpm
        )
        val target = clip.timelineStartFrame + 40_000L

        viewModel.moveClipToFrame(track.index, clip.id, rawTargetFrame = target)
        val moved = viewModel.session.value.tracks.first().clips.first { it.id == clip.id }
        assertNotEquals(clip.timelineStartFrame, moved.timelineStartFrame)
        assertEquals(beatFrames * 2, moved.timelineStartFrame)
        assertEquals(0L, moved.timelineStartFrame % beatFrames)

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
        val viewModel = newViewModel()

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
        val viewModel = newViewModel()
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
        val viewModel = newViewModel()
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
