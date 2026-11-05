package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Take-lane behaviour through the ViewModel: recording passes become takes,
 * switching is undoable, and deletion falls back to another take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TakeLaneViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // Start from a clean 16-track session: a snapshot from another test
        // would otherwise supply clips this fixture does not expect.
        application.deleteFile("recovery_journal.json")
        application.filesDir.resolve("projects").deleteRecursively()
        viewModel = DawViewModel(application)
        viewModel.useRecorderForTest(FakeAudioRecorder())
        runBlocking { viewModel.awaitSessionReady() }
        viewModel.installTestClip(0)
    }


    @After
    fun tearDown() {
        viewModel.onCleared()
    }

    private fun recordTake(trackIndex: Int) {
        if (!viewModel.session.value.tracks[trackIndex].isArmedForRecording) {
            viewModel.toggleTrackArm(trackIndex)
        }
        viewModel.startRecording()
        assertTrue("Recorder must start", viewModel.uiState.value.isRecording)
        viewModel.stopRecording()
        assertTrue("Recording must stop", !viewModel.uiState.value.isRecording)
    }

    @Test
    fun secondPassBecomesItsOwnTakeAndKeepsTheFirst() {
        recordTake(0)

        val track = viewModel.session.value.tracks[0]
        assertEquals("A take lane must be created", 2, track.takeLanes.size)
        assertEquals("Take 1", track.takeLanes[0].name)
        assertEquals("The pre-existing clip belongs to Take 1", 1, track.takeLanes[0].clips.size)
        assertEquals("Take 2", track.takeLanes[1].name)
        assertEquals(track.takeLanes[1].id, track.activeTakeId)
        assertEquals("Only the active take is audible", 1, track.clips.size)
    }

    @Test
    fun selectingATakeSwapsTheAudibleClipsAndIsUndoable() {
        recordTake(0)
        val firstLane = viewModel.session.value.tracks[0].takeLanes[0]
        val secondLane = viewModel.session.value.tracks[0].takeLanes[1]
        assertNotEquals(firstLane.id, secondLane.id)

        viewModel.selectTakeLane(0, firstLane.id)
        val afterSwitch = viewModel.session.value.tracks[0]
        assertEquals(firstLane.id, afterSwitch.activeTakeId)
        assertEquals(firstLane.clips.map { it.id }, afterSwitch.clips.map { it.id })

        viewModel.undo()
        val afterUndo = viewModel.session.value.tracks[0]
        assertEquals("Undo must restore the previously selected take", secondLane.id, afterUndo.activeTakeId)
        assertEquals(secondLane.clips.map { it.id }, afterUndo.clips.map { it.id })
    }

    @Test
    fun deletingTheActiveTakeFallsBackToAnotherTake() {
        recordTake(0)
        val track = viewModel.session.value.tracks[0]
        val firstLane = track.takeLanes[0]
        val activeLane = track.takeLanes.first { it.id == track.activeTakeId }

        viewModel.deleteTakeLane(0, activeLane.id)

        val after = viewModel.session.value.tracks[0]
        assertEquals(1, after.takeLanes.size)
        assertEquals(firstLane.id, after.activeTakeId)
        assertEquals(firstLane.clips.map { it.id }, after.clips.map { it.id })
    }

    @Test
    fun deletingTheOnlyTakeIsRefused() {
        recordTake(0)
        val track = viewModel.session.value.tracks[0]
        viewModel.deleteTakeLane(0, track.takeLanes[1].id)
        val remaining = viewModel.session.value.tracks[0].takeLanes.single()

        viewModel.deleteTakeLane(0, remaining.id)

        assertEquals(1, viewModel.session.value.tracks[0].takeLanes.size)
        assertTrue(
            "Deleting the last take must explain why it was refused",
            viewModel.uiState.value.statusMessage?.contains("Keep at least one take") == true
        )
    }

    @Test
    fun ratingCyclesThroughStarsAndBackToUnrated() {
        recordTake(0)
        val laneId = viewModel.session.value.tracks[0].takeLanes[1].id

        repeat(5) { viewModel.rateTakeLane(0, laneId) }
        assertEquals(5, ratingOf(laneId))

        viewModel.rateTakeLane(0, laneId)
        assertEquals(0, ratingOf(laneId))
    }

    @Test
    fun ratingTheActiveTakeSurvivesTakeSwitching() {
        recordTake(0)
        val track = viewModel.session.value.tracks[0]
        val first = track.takeLanes[0].id
        val second = track.takeLanes[1].id
        viewModel.rateTakeLane(0, first)
        viewModel.rateTakeLane(0, first)

        viewModel.selectTakeLane(0, first)
        viewModel.selectTakeLane(0, second)

        assertEquals("Rating must not be lost when its take is left", 2, ratingOf(first))
    }

    @Test
    fun compingARangeFromAnAlternateTakeUsesThatAudioOnTheTimeline() {
        recordTake(0)
        val track = viewModel.session.value.tracks[0]
        val first = track.takeLanes[0]
        val second = track.takeLanes[1]
        viewModel.selectTakeLane(0, first.id)

        viewModel.compTakeRange(0, second.id, 0L, 48_000L)

        val comped = viewModel.session.value.tracks[0].clips.sortedBy { it.timelineStartFrame }
        assertEquals("Borrowed range plus the untouched remainder", 2, comped.size)
        val borrowed = comped.first()
        assertEquals(0L, borrowed.timelineStartFrame)
        assertEquals(48_000L, borrowed.lengthFrames)
        assertEquals(
            "The comped piece must carry the alternate take's audio",
            second.clips.single().sourcePath,
            borrowed.sourcePath
        )
        val remainder = comped.last()
        assertEquals(48_000L, remainder.timelineStartFrame)
        assertEquals(720_000L, remainder.lengthFrames)
        assertEquals("Take 2 stays the auditioned take", first.id, viewModel.session.value.tracks[0].activeTakeId)

        viewModel.undo()
        assertEquals(
            "Undo must put the uncomped take back",
            first.clips.map { it.id },
            viewModel.session.value.tracks[0].clips.map { it.id }
        )
    }

    @Test
    fun compingFromTheAudibleTakeIsRefused() {
        recordTake(0)
        val active = viewModel.session.value.tracks[0]
        val activeId = requireNotNull(active.activeTakeId)
        val before = active.clips.map { it.id }

        viewModel.compTakeRange(0, activeId, 0L, 48_000L)

        assertEquals(before, viewModel.session.value.tracks[0].clips.map { it.id })
        assertTrue(
            "The refusal must say why",
            viewModel.uiState.value.statusMessage?.contains("already the audible take") == true
        )
    }

    @Test
    fun compingAnEmptyRangeIsRejected() {
        recordTake(0)
        val track = viewModel.session.value.tracks[0]
        val before = track.clips.map { it.id }

        viewModel.compTakeRange(0, track.takeLanes[0].id, 10_000L, 10_100L)

        assertEquals(before, viewModel.session.value.tracks[0].clips.map { it.id })
        assertTrue(viewModel.uiState.value.statusMessage?.contains("Select a longer range") == true)
    }

    private fun ratingOf(laneId: String): Int =
        viewModel.session.value.tracks[0].takeLanes.first { it.id == laneId }.rating
}
