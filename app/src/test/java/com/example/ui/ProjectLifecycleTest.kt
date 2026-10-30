package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.history.ProjectPersistence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectLifecycleTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        application.deleteFile("recovery_journal.json")
        File(application.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun saveAsWritesANamedProjectAndTheLastSessionSnapshot() = runBlocking {
        val viewModel = DawViewModel(application)
        try {
            viewModel.awaitSessionReady()
            viewModel.installTestClip(0)
            val clipCount = viewModel.session.value.tracks[0].clips.size

            viewModel.saveProjectAs("My Song")
            waitUntil(30_000) { viewModel.projectState.value.currentPath != null }

            val path = viewModel.projectState.value.currentPath!!
            assertTrue(
                "save should use the project name",
                File(path).nameWithoutExtension == "session_My_Song",
            )
            assertTrue(File(path).exists())

            val persistence = ProjectPersistence(application)
            val loaded = persistence.loadProject(File(path))
            assertEquals("My Song", loaded?.projectName)
            assertEquals(clipCount, loaded?.tracks?.get(0)?.clips?.size)

            val last = persistence.loadLastSession()
            assertEquals("My Song", last?.projectName)
            assertTrue(
                "the saved project must appear in Open",
                persistence.listSavedProjects().any { it.file.absolutePath == path },
            )
        } finally {
            viewModel.onCleared()
        }
    }

    @Test
    fun newProjectClearsTheSessionAndReplacesTheRestoredSnapshot() = runBlocking {
        val viewModel = DawViewModel(application)
        try {
            viewModel.awaitSessionReady()
            viewModel.installTestClip(0)
            assertTrue(viewModel.session.value.tracks[0].clips.isNotEmpty())

            val savedBefore = viewModel.projectState.value.lastSavedAt
            viewModel.newProject()
            waitUntil(10_000) {
                viewModel.session.value.tracks.all { it.clips.isEmpty() } &&
                    viewModel.projectState.value.currentPath == null &&
                    viewModel.projectState.value.lastSavedAt > savedBefore
            }

            assertNull(viewModel.projectState.value.currentPath)
            val last = ProjectPersistence(application).loadLastSession()
            assertTrue(last != null && last.tracks.all { it.clips.isEmpty() })
        } finally {
            viewModel.onCleared()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }
}
