package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.model.EditMode
import com.example.audio.model.FadeCurve
import com.example.audio.model.TimelineTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pro Tools style timeline editing: both-edge trimming, separate/heal, shuffle
 * packing, crossfades, nudge and the edit (time) selection.
 *
 * Session fixture: one 16 s take at frame 0 on track 0, 48 kHz / 120 BPM, so a
 * beat is 24 000 frames (0.5 s) and a bar is 96 000 frames (2 s).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimelineEditingTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    private val clips get() = viewModel.session.value.tracks[0].clips

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        viewModel = DawViewModel(application)
        viewModel.installTestClip(0)
    }

    @Test
    fun trimOutPoint_shortensTheClipAndClampsItsFades() {
        val id = clips.single().id
        // A 30 s fade written over a 16 s clip: the trim has to bring it back
        // inside the new length instead of leaving it hanging past the end.
        viewModel.setClipFadeOut(0, id, 30_000f)
        assertEquals(1_440_000L, clips.single().fadeOutFrames)

        viewModel.trimClipEndToFrame(0, id, 48_000L)

        val trimmed = clips.single()
        assertEquals(48_000L, trimmed.lengthFrames)
        assertEquals(0L, trimmed.timelineStartFrame)
        assertEquals(48_000L, trimmed.fadeOutFrames)
    }

    @Test
    fun trimInPoint_movesTheClipAndKeepsItsAudioLockedToTheTimeline() {
        val id = clips.single().id

        viewModel.trimClipStartToFrame(0, id, 48_000L)

        val trimmed = clips.single()
        assertEquals(48_000L, trimmed.timelineStartFrame)
        assertEquals(48_000L, trimmed.sourceStartFrame)
        assertEquals(720_000L, trimmed.lengthFrames)
        // Content lock: the source offset stays level with the timeline start.
        assertEquals(
            trimmed.timelineStartFrame - trimmed.sourceStartFrame,
            clips.single().timelineStartFrame - clips.single().sourceStartFrame
        )
    }

    @Test
    fun trimInPoint_hardSnapsToTheGridInGridMode() {
        val id = clips.single().id

        viewModel.trimClipStartToFrame(0, id, 25_000L, snapToleranceFrames = 960L)

        // Nearest beat at 120 BPM is 24 000 frames, even though the drag was
        // nowhere near a magnetic snap window: Grid mode is a hard snap.
        assertEquals(24_000L, clips.single().timelineStartFrame)
        assertEquals(744_000L, clips.single().lengthFrames)
    }

    @Test
    fun trimInPoint_extendsBackOnlyAsFarAsTheTakeAllows() {
        val id = clips.single().id

        viewModel.trimClipStartToFrame(0, id, 48_000L)
        assertEquals(48_000L, clips.single().sourceStartFrame)
        assertEquals(720_000L, clips.single().lengthFrames)

        // Dragging the in point back left uncovers the head of the take again.
        viewModel.trimClipStartToFrame(0, id, 0L)
        assertEquals(0L, clips.single().timelineStartFrame)
        assertEquals(0L, clips.single().sourceStartFrame)
        assertEquals(768_000L, clips.single().lengthFrames)

        // A clip placed later on the timeline has no source left of frame 0, so
        // a further drag left must not stretch it over audio that is not there.
        viewModel.moveClipToFrame(0, id, 24_000L)
        viewModel.trimClipStartToFrame(0, id, -48_000L)
        assertEquals(24_000L, clips.single().timelineStartFrame)
        assertEquals(0L, clips.single().sourceStartFrame)
        assertEquals(768_000L, clips.single().lengthFrames)
    }

    @Test
    fun separateAtSelection_cutsAtBothSelectionEdges() {
        viewModel.setEditSelection(0, 200_000L, 400_000L)

        val cuts = viewModel.separateAtSelection()

        assertEquals(2, cuts)
        val parts = clips.sortedBy { it.timelineStartFrame }
        assertEquals(3, parts.size)
        assertEquals(listOf(0L, 200_000L, 400_000L), parts.map { it.timelineStartFrame })
        assertEquals(listOf(200_000L, 200_000L, 368_000L), parts.map { it.lengthFrames })
        // The second and third parts still point at their own slice of the take.
        assertEquals(listOf(0L, 200_000L, 400_000L), parts.map { it.sourceStartFrame })
    }

    @Test
    fun separateClipsAtFrame_refusesCutsTooCloseToAnEdge() {
        val id = clips.single().id

        assertEquals(0, viewModel.separateClipsAtFrame(1_000L, 0))
        assertEquals(1, clips.size)
        assertEquals(id, clips.single().id)
        assertEquals(768_000L, clips.single().lengthFrames)
    }

    @Test
    fun separateThenHeal_restoresOneClipAndUndoStepsBackThroughBoth() {
        val separated = viewModel.separateClipsAtFrame(100_000L, 0)
        assertEquals(1, separated)

        val parts = clips.sortedBy { it.timelineStartFrame }
        assertEquals(2, parts.size)
        assertEquals(100_000L, parts[1].sourceStartFrame)
        assertEquals(768_000L, parts.sumOf { it.lengthFrames })

        assertTrue(viewModel.healSeparation(0, parts[1].id))

        val healed = clips.single()
        assertEquals(0L, healed.timelineStartFrame)
        assertEquals(0L, healed.sourceStartFrame)
        assertEquals(768_000L, healed.lengthFrames)
        assertEquals("Test Take 1", healed.name)

        // Undo unwinds the heal first, then the separation.
        viewModel.undo()
        assertEquals(2, clips.size)
        viewModel.undo()
        assertEquals(1, clips.size)
        assertEquals(768_000L, clips.single().lengthFrames)
    }

    @Test
    fun moveClipInShuffleMode_packsFollowingClipsFlush() {
        val first = clips.single().id
        viewModel.duplicateClip(0, first)
        val copy = clips.first { it.id != first }
        assertEquals(768_000L, copy.timelineStartFrame)

        viewModel.setEditMode(EditMode.SHUFFLE)
        viewModel.moveClipToFrame(0, first, 480_000L)

        val shuffled = clips.sortedBy { it.timelineStartFrame }
        assertEquals(480_000L, shuffled[0].timelineStartFrame)
        // The copy follows the moved clip with no gap and no overlap.
        assertEquals(480_000L + 768_000L, shuffled[1].timelineStartFrame)
    }

    @Test
    fun moveClipInGridMode_alwaysLandsOnAGridLine() {
        val id = clips.single().id

        viewModel.moveClipToFrame(0, id, 25_000L, snapToleranceFrames = 960L)

        // 24 000 frames = one beat at 120 BPM / 48 kHz.
        assertEquals(24_000L, clips.single().timelineStartFrame)
    }

    @Test
    fun spotMode_placesTheClipExactlyAndReportsTheTime() {
        val id = clips.single().id
        viewModel.setEditMode(EditMode.SPOT)

        viewModel.beginClipGesture(0, "Spot clip")
        viewModel.moveClipToFrame(0, id, 25_000L)
        viewModel.endClipGesture()

        // Spot mode does not snap: the clip sits exactly where it was dropped.
        assertEquals(25_000L, clips.single().timelineStartFrame)
        // ...and the landed time is read back once the drop settles.
        val status = viewModel.uiState.value.statusMessage
        assertNotNull(status)
        assertTrue("expected a spot readout, got: $status", status!!.startsWith("Spot:"))
        assertTrue("expected the 00:00.52 timestamp in: $status", status.contains("00:00.52"))
    }

    @Test
    fun spotAtFrame_placesTheClipExactlyEvenInGridMode() {
        val id = clips.single().id
        // Grid mode would snap a drop to 24 000 frames; a spot is exact.
        assertTrue(viewModel.spotClipAtFrame(0, id, 25_000L))

        assertEquals(25_000L, clips.single().timelineStartFrame)
        assertTrue(viewModel.uiState.value.statusMessage.orEmpty().contains("00:00.52"))

        viewModel.undo()
        assertEquals(0L, clips.single().timelineStartFrame)
    }

    @Test
    fun spotByEnd_alignsTheOutPoint() {
        val id = clips.single().id

        assertTrue(viewModel.spotClipAtFrame(0, id, 800_000L, placeByEnd = true))

        val placed = clips.single()
        assertEquals(800_000L, placed.timelineStartFrame + placed.lengthFrames)
        assertEquals(32_000L, placed.timelineStartFrame)
    }

    @Test
    fun spotAtTheSameFrameIsANoOp() {
        val id = clips.single().id

        assertFalse(viewModel.spotClipAtFrame(0, id, 0L))

        assertEquals(0L, clips.single().timelineStartFrame)
        assertTrue(viewModel.uiState.value.statusMessage.orEmpty().contains("already at"))
    }

    @Test
    fun nudgeSelection_stepsByGridAndBarThenClampsAtZero() {
        val id = clips.single().id
        viewModel.toggleClipSelection(0, id)

        assertEquals(1, viewModel.nudgeSelection(forward = true))
        assertEquals(24_000L, clips.single().timelineStartFrame)

        viewModel.nudgeSelection(forward = true, byBar = true)
        assertEquals(120_000L, clips.single().timelineStartFrame)

        repeat(6) { viewModel.nudgeSelection(forward = false) }
        assertEquals(0L, clips.single().timelineStartFrame)
        assertEquals(id, clips.single().id)
    }

    @Test
    fun nudgeSelection_withoutASelectionExplainsItself() {
        assertEquals(0, viewModel.nudgeSelection(forward = true))
        assertTrue(viewModel.uiState.value.statusKind == StatusKind.ERROR)
    }

    @Test
    fun crossfade_needsTwoNeighboursAndAppliesEqualPowerFades() {
        val first = clips.single().id
        viewModel.toggleClipSelection(0, first)
        assertFalse(viewModel.crossfadeSelectedClips())

        viewModel.duplicateClip(0, first)
        val copy = clips.first { it.id != first }
        viewModel.toggleClipSelection(0, copy.id)

        assertTrue(viewModel.crossfadeSelectedClips())

        val faded = clips.associateBy { it.id }
        // 10 ms default crossfade at 48 kHz.
        assertEquals(480L, faded.getValue(first).fadeOutFrames)
        assertEquals(480L, faded.getValue(copy.id).fadeInFrames)
        assertEquals(FadeCurve.EQUAL_POWER, faded.getValue(first).fadeOutCurve)
        assertEquals(FadeCurve.EQUAL_POWER, faded.getValue(copy.id).fadeInCurve)
    }

    @Test
    fun deleteSelectedClips_clearsOnlyTheSelectionAndUndoBringsItBack() {
        viewModel.installTestClip(0, lengthFrames = 24_000L)
        val clipsBefore = clips.sortedBy { it.timelineStartFrame }
        assertEquals(2, clipsBefore.size)
        val doomed = clipsBefore[0].id

        viewModel.toggleClipSelection(0, doomed)
        assertEquals(1, viewModel.deleteSelectedClips())

        assertEquals(1, clips.size)
        assertEquals(clipsBefore[1].id, clips.single().id)
        assertTrue(viewModel.uiState.value.selectedClipIds.isEmpty())

        viewModel.undo()
        assertEquals(2, clips.size)
    }

    @Test
    fun editSelection_clampsToTheSessionAndDropsStrayDrags() {
        viewModel.setEditSelection(0, 1_000L, 5_000L)
        val selection = viewModel.uiState.value.editSelection
        assertNotNull(selection)
        assertEquals(4_000L, selection!!.lengthFrames)

        // A drag shorter than half a grid step is a stray tap, not a selection.
        viewModel.setEditSelection(0, 1_000L, 2_000L)
        viewModel.commitEditSelection()
        assertNull(viewModel.uiState.value.editSelection)

        // A longer drag survives the commit.
        viewModel.setEditSelection(0, 1_000L, 20_000L)
        viewModel.commitEditSelection()
        assertNotNull(viewModel.uiState.value.editSelection)

        // Out-of-range frames are clamped into the session.
        viewModel.setEditSelection(0, 900_000L, 1_000_000L)
        val clamped = viewModel.uiState.value.editSelection!!
        assertEquals(767_999L, clamped.startFrame)
        assertEquals(768_000L, clamped.endFrame)

        viewModel.clearEditSelection()
        assertNull(viewModel.uiState.value.editSelection)
    }

    @Test
    fun timelineToolAndEditMode_persistAcrossViewModels() {
        viewModel.setTimelineTool(TimelineTool.TRIM)
        viewModel.setEditMode(EditMode.SHUFFLE)

        val reopened = DawViewModel(application)
        try {
            assertEquals(TimelineTool.TRIM, reopened.settings.value.timelineTool)
            assertEquals(EditMode.SHUFFLE, reopened.settings.value.editMode)
        } finally {
            reopened.onCleared()
        }
    }
}
