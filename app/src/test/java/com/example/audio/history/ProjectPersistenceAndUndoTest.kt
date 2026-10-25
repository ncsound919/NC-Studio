package com.example.audio.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.model.AudioClip
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectPersistenceAndUndoTest {

    @Test
    fun undoManager_pushUndoRedoStackOperations() {
        val undoManager = UndoManager(maxHistory = 10)
        assertFalse(undoManager.canUndo())
        assertFalse(undoManager.canRedo())

        val initialSession = ProjectSession(revision = 1L)
        val track0Before = TrackData(index = 0, name = "Track 0", colorHex = 0xFFFF5722, gainDb = 0f)
        val track0After = TrackData(index = 0, name = "Track 0", colorHex = 0xFFFF5722, gainDb = -6f)

        val command1 = SessionCommand.UpdateTrackParams(
            description = "Fader gain Track 0",
            trackIndex = 0,
            beforeTrack = track0Before,
            afterTrack = track0After
        )

        undoManager.pushAction(command1)
        assertTrue(undoManager.canUndo())
        assertFalse(undoManager.canRedo())

        // Execute Undo
        val undoResult = undoManager.undo(initialSession.copy(tracks = listOf(track0After)))
        assertNotNull(undoResult)
        val (restoredSession, executedCmd) = undoResult!!
        assertEquals("Fader gain Track 0", executedCmd.description)
        assertEquals(0f, restoredSession.tracks[0].gainDb, 0.001f) // Undo restores before state
        assertTrue(undoManager.canRedo())

        // Execute Redo
        val redoResult = undoManager.redo(restoredSession)
        assertNotNull(redoResult)
        val (redoneSession, _) = redoResult!!
        assertEquals(-6f, redoneSession.tracks[0].gainDb, 0.001f)
    }

    @Test
    fun projectPersistence_crashRecoveryJournalWriteAndRestore() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val persistence = ProjectPersistence(app)

        val sessionToJournal = ProjectSession(
            projectName = "Test Persistence Project",
            revision = 42L,
            tempoBpm = 128f,
            masterGainDb = -1.5f,
            tracks = listOf(
                TrackData(
                    index = 0,
                    name = "Recorded Kick",
                    colorHex = 0xFFFF5722,
                    gainDb = -2.0f,
                    clips = listOf(
                        AudioClip(
                            name = "Kick Take 1",
                            sourcePath = "take_1.wav",
                            lengthFrames = 48000L,
                            trackIndex = 0
                        )
                    )
                )
            )
        )

        persistence.writeCrashRecoveryJournal(sessionToJournal)
        assertTrue("Crash recovery journal file should exist", persistence.hasCrashRecoveryJournal())

        val restoredSession = persistence.restoreFromCrashRecoveryJournal()
        assertNotNull(restoredSession)
        assertEquals("Test Persistence Project", restoredSession!!.projectName)
        assertEquals(42L, restoredSession.revision)
        assertEquals(128f, restoredSession.tempoBpm, 0.1f)
        assertEquals(-1.5f, restoredSession.masterGainDb, 0.1f)
        assertEquals(1, restoredSession.tracks.size)
        assertEquals("Recorded Kick", restoredSession.tracks[0].name)
        assertEquals("Kick Take 1", restoredSession.tracks[0].clips[0].name)

        // Clear crash journal by saving a project
        persistence.saveProject(restoredSession)
        assertFalse(persistence.hasCrashRecoveryJournal())
    }
}
