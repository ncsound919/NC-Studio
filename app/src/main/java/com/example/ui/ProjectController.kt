package com.example.ui

import android.content.SharedPreferences
import com.example.audio.history.ProjectPersistence
import com.example.audio.model.DawSettings
import com.example.audio.model.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns named projects, the last-session snapshot, auto-save, and the Open /
 * Save As dialog state. Session hydration stays in the ViewModel because it
 * touches the audio engine; this controller only decides *which* session.
 */
class ProjectController(
    private val scope: CoroutineScope,
    private val persistence: ProjectPersistence,
    private val prefs: SharedPreferences,
    private val currentSession: () -> ProjectSession,
    private val updateSession: (ProjectSession) -> Unit,
    private val newBlankSession: () -> ProjectSession,
    private val applySession: suspend (ProjectSession, Boolean) -> Unit,
    private val stopTransport: () -> Unit,
    private val clearHistory: () -> Unit,
    private val onStatus: (String, StatusKind) -> Unit,
) {

    private val _state = MutableStateFlow(
        ProjectUiState(
            currentName = currentSession().projectName,
            currentPath = prefs.getString(CURRENT_PROJECT_PREF, null),
            lastSavedAt = prefs.getLong(LAST_SAVED_PREF, 0L),
        ),
    )
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    // Revision of the last clean save, so auto-save never rewrites an idle session.
    private var lastPersistedRevision = Long.MIN_VALUE

    fun markLoaded(session: ProjectSession) {
        lastPersistedRevision = session.revision
        _state.update {
            it.copy(
                currentName = session.projectName,
                currentPath = prefs.getString(CURRENT_PROJECT_PREF, null),
                lastSavedAt = prefs.getLong(LAST_SAVED_PREF, 0L),
            )
        }
    }

    fun markAutoSaved(atEpochMs: Long) {
        prefs.edit().putLong(LAST_SAVED_PREF, atEpochMs).apply()
        _state.update { it.copy(lastSavedAt = atEpochMs) }
    }

    fun showDialog(mode: ProjectDialogMode) {
        refreshSavedProjects()
        _state.update { it.copy(showDialog = true, dialogMode = mode) }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false) }
    }

    private fun refreshSavedProjects() {
        val saved = persistence.listSavedProjects().map {
            ProjectFileInfo(file = it.file, name = it.name, modifiedAt = it.modifiedAt)
        }
        _state.update { it.copy(savedProjects = saved) }
    }

    fun saveCurrent() {
        val path = _state.value.currentPath
        if (path == null) {
            showDialog(ProjectDialogMode.SAVE_AS)
            return
        }
        saveTo(File(path), currentSession().projectName)
    }

    fun saveAs(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        saveTo(null, clean)
    }

    private fun saveTo(existing: File?, name: String) {
        if (_state.value.isSaving) return
        val snapshot = currentSession().copy(
            projectName = name,
            revision = currentSession().revision + 1,
        )
        _state.update { it.copy(isSaving = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val saved = if (existing == null) persistence.saveProject(snapshot)
                else persistence.saveProject(snapshot, existing.name)
                // The session only adopts the new name/revision after the bytes
                // are on disk, so a failed save cannot advance the clean state.
                updateSession(snapshot)
                lastPersistedRevision = snapshot.revision
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putString(CURRENT_PROJECT_PREF, saved.absolutePath)
                    .putLong(LAST_SAVED_PREF, now)
                    .apply()
                _state.update {
                    it.copy(
                        currentName = snapshot.projectName,
                        currentPath = saved.absolutePath,
                        isSaving = false,
                        showDialog = false,
                        lastSavedAt = now,
                    )
                }
                refreshSavedProjects()
                onStatus("Saved \"${snapshot.projectName}\" to project storage.", StatusKind.SUCCESS)
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                onStatus(
                    "Could not save \"${snapshot.projectName}\": ${e.message ?: "storage error"}.",
                    StatusKind.ERROR,
                )
            }
        }
    }

    fun open(file: File) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = persistence.loadProject(file)
                if (loaded == null) {
                    onStatus(
                        "Could not open ${file.name}. The file is damaged or not an NC Studio project.",
                        StatusKind.ERROR,
                    )
                    return@launch
                }
                stopTransport()
                applySession(loaded, true)
                lastPersistedRevision = loaded.revision
                prefs.edit().putString(CURRENT_PROJECT_PREF, file.absolutePath).apply()
                _state.update {
                    it.copy(
                        currentName = loaded.projectName,
                        currentPath = file.absolutePath,
                        showDialog = false,
                    )
                }
                refreshSavedProjects()
                onStatus("Opened \"${loaded.projectName}\".", StatusKind.SUCCESS)
            } catch (e: Exception) {
                onStatus(
                    "Could not open ${file.name}: ${e.message ?: "load error"}.",
                    StatusKind.ERROR,
                )
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    fun newProject() {
        scope.launch {
            stopTransport()
            clearHistory()
            val blank = newBlankSession()
            applySession(blank, true)
            prefs.edit().remove(CURRENT_PROJECT_PREF).apply()
            _state.update {
                it.copy(
                    currentName = blank.projectName,
                    currentPath = null,
                    isSaving = false,
                    showDialog = false,
                )
            }
            refreshSavedProjects()
            onStatus("New project ready.", StatusKind.SUCCESS)
        }
    }

    /**
     * Saves the live session every [DawSettings.autoSaveMinutes]. Zero disables
     * auto-save; the crash journal still covers edits between intervals.
     */
    fun startAutoSave(settings: StateFlow<DawSettings>, ready: () -> Boolean) {
        scope.launch {
            settings.map { it.autoSaveMinutes }.distinctUntilChanged().collectLatest { minutes ->
                if (minutes <= 0) return@collectLatest
                while (isActive) {
                    delay(minutes * 60_000L)
                    autoSave(ready)
                }
            }
        }
    }

    private suspend fun autoSave(ready: () -> Boolean) {
        if (!ready()) return
        val snapshot = currentSession()
        if (snapshot.revision == lastPersistedRevision) return
        withContext(Dispatchers.IO) {
            runCatching { persistence.saveLastSession(snapshot) }
                .onSuccess {
                    lastPersistedRevision = snapshot.revision
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(LAST_SAVED_PREF, now).apply()
                    _state.update { it.copy(lastSavedAt = now) }
                }
        }
    }

    companion object {
        const val CURRENT_PROJECT_PREF = "current_project_path"
        const val LAST_SAVED_PREF = "last_saved_at"
    }
}
