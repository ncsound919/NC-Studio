package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.audio.model.ProjectSession
import com.example.audio.model.StemCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end proof of the NC Phone Beat -> NC Studio import: the golden package
 * (written by NC Phone Beat's own exporter) is read through the SAF-style stream
 * path, and the resulting session must carry the tempo, the per-track mix, playable
 * stem clips and the embedded beat source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NcbeatImportEndToEndTest {

    private lateinit var application: Application
    private lateinit var viewModel: DawViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        viewModel = DawViewModel(application)
    }

    @After
    fun tearDown() {
        viewModel.onCleared()
    }

    private fun awaitBeatSession(timeoutMs: Long = 20_000): ProjectSession {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val err = viewModel.uiState.value.importError
            if (err != null) fail("import failed: $err")
            val session = viewModel.session.value
            if (session.beatSource != null) return session
            Thread.sleep(50)
        }
        fail("timed out waiting for the beat package import")
        throw AssertionError("unreachable")
    }

    @Test
    fun `importing the golden package populates the session natively`() {
        val fixture = File(application.cacheDir, "ncbeat_v1.ncbeat")
        val stream = javaClass.classLoader!!.getResourceAsStream("ncbeat_v1.ncbeat")
            ?: run { fail("golden fixture missing from test resources"); return }
        fixture.writeBytes(stream.readBytes())

        viewModel.importNcbeat(Uri.fromFile(fixture))
        val session = awaitBeatSession()

        // Embedded source: the DAW knows exactly what was imported.
        val beat = session.beatSource!!
        assertEquals("fixture beat", beat.patternName)
        assertEquals("ncbeat.v1", beat.schema)
        assertTrue(beat.patternJson.contains("drum.v1"))
        assertTrue(beat.mixerJson.contains("tracks"))

        // Tempo + master from the package.
        assertEquals(300f, session.tempoBpm, 1e-6f)
        assertEquals(0f, session.masterGainDb, 1e-6f)

        // Track mapping: package tracks land on session tracks 0 and 1 with mix data.
        val kit = session.tracks[0]
        assertEquals("Kit", kit.name)
        assertEquals(StemCategory.DRUMS, kit.stemCategory)
        assertEquals(1, kit.clips.size)
        assertEquals(0f, kit.gainDb, 1e-6f)

        val bass = session.tracks[1]
        assertEquals("Bass", bass.name)
        assertEquals(StemCategory.BASS, bass.stemCategory)
        assertEquals(1, bass.clips.size)
        assertEquals(-2.4987f, bass.gainDb, 0.001f)
        assertEquals(-0.2f, bass.pan, 1e-6f)

        // Clips are real, persisted WAVs of one loop (4 800 frames at 48 kHz).
        for (track in listOf(kit, bass)) {
            val clip = track.clips[0]
            assertEquals(4_800L, clip.lengthFrames)
            assertEquals(0L, clip.timelineStartFrame)
            val file = File(clip.sourcePath)
            assertTrue("stem file must exist: ${clip.sourcePath}", file.isFile && file.length() > 44)
        }

        // Untouched tracks stay untouched, and no error was reported.
        assertTrue(session.tracks[5].clips.isEmpty())
        assertNull(viewModel.uiState.value.importError)
        assertTrue("timeline must cover the loop", session.totalLengthFrames >= 4_800L)
    }

    @Test
    fun `importing a non-package file reports a clean error`() {
        val bogus = File(application.cacheDir, "not_a_package.ncbeat")
        bogus.writeBytes("definitely not a zip".toByteArray())

        viewModel.importNcbeat(Uri.fromFile(bogus))
        val deadline = System.currentTimeMillis() + 20_000
        var error: String? = null
        while (System.currentTimeMillis() < deadline) {
            error = viewModel.uiState.value.importError
            if (error != null) break
            Thread.sleep(50)
        }
        assertNotNull("a bad file must surface an import error", error)
        assertNull(viewModel.session.value.beatSource)
    }
}