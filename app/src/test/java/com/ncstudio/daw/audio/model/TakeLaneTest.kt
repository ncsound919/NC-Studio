package com.ncstudio.daw.audio.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ncstudio.daw.audio.history.ProjectPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TakeLaneTest {

    private fun clip(name: String, trackIndex: Int = 0) = AudioClip(
        name = name,
        sourcePath = "$name.wav",
        lengthFrames = 48_000L,
        trackIndex = trackIndex
    )

    private fun trackWithTake() = TrackData(
        index = 0,
        name = "Vocal",
        colorHex = 0xFFFF5722,
        clips = listOf(clip("first"))
    )

    @Test
    fun withNewTake_promotesExistingClipsToTakeOne() {
        val original = trackWithTake()
        val second = TakeLane(name = "Take 2", clips = listOf(clip("second")))

        val updated = original.withNewTake(second)

        assertEquals(2, updated.takeLanes.size)
        assertEquals("Take 1", updated.takeLanes[0].name)
        assertEquals("first", updated.takeLanes[0].clips.single().name)
        assertEquals(second.id, updated.activeTakeId)
        assertEquals("second", updated.clips.single().name)
    }

    @Test
    fun withActiveTake_swapsClipsWithoutLosingTheOtherTake() {
        val second = TakeLane(name = "Take 2", clips = listOf(clip("second")))
        val withTwo = trackWithTake().withNewTake(second)
        val firstLaneId = withTwo.takeLanes[0].id

        val switchedBack = withTwo.withActiveTake(firstLaneId)

        assertEquals(firstLaneId, switchedBack.activeTakeId)
        assertEquals("first", switchedBack.clips.single().name)
        // The take we left still holds its own material.
        assertEquals("second", switchedBack.takeLanes.first { it.id == second.id }.clips.single().name)

        val switchedAgain = switchedBack.withActiveTake(second.id)
        assertEquals("second", switchedAgain.clips.single().name)
        assertEquals("first", switchedAgain.takeLanes.first { it.id == firstLaneId }.clips.single().name)
    }

    @Test
    fun withActiveTake_unknownLaneIsANoOp() {
        val track = trackWithTake()

        assertEquals(track, track.withActiveTake("does-not-exist"))
    }

    @Test
    fun audioSourcePathsIncludeInactiveTakes() {
        val withTwo = trackWithTake().withNewTake(
            TakeLane(name = "Take 2", clips = listOf(clip("second")))
        )

        assertEquals(setOf("first.wav", "second.wav"), withTwo.audioSourcePaths)
    }

    @Test
    fun takeLanes_surviveProjectPersistence() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteFile("recovery_journal.json")
        app.filesDir.resolve("projects").deleteRecursively()
        val persistence = ProjectPersistence(app)

        val session = ProjectSession(
            projectName = "Takes",
            tracks = listOf(trackWithTake().withNewTake(
                TakeLane(name = "Take 2", clips = listOf(clip("second")), rating = 4)
            ))
        )

        persistence.writeCrashRecoveryJournal(session)
        val restored = persistence.restoreFromCrashRecoveryJournal()

        assertTrue(restored != null)
        val track = restored!!.tracks.single()
        assertEquals(2, track.takeLanes.size)
        assertEquals(0, track.takeLanes[0].rating)
        assertEquals(4, track.takeLanes[1].rating)
        assertEquals(track.takeLanes[1].id, track.activeTakeId)
        assertNotEquals(null, track.activeTakeId)
        assertEquals("second", track.clips.single().name)

        app.deleteFile("recovery_journal.json")
        app.filesDir.resolve("projects").deleteRecursively()
    }

    @Test
    fun legacyTrackWithoutTakeLanesStillLoads() {
        val track = trackWithTake()

        assertTrue(track.takeLanes.isEmpty())
        assertNull(track.activeTakeId)
        assertEquals(1, track.clips.size)
    }
}
