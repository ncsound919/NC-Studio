package com.example.audio.history

import android.content.Context
import com.example.audio.model.AudioClip
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Undoable action representing state changes (zero audio duplicated).
 */
sealed interface SessionCommand {
    val description: String

    data class EditClips(
        override val description: String,
        val trackIndex: Int,
        val beforeClips: List<AudioClip>,
        val afterClips: List<AudioClip>
    ) : SessionCommand

    data class UpdateTrackParams(
        override val description: String,
        val trackIndex: Int,
        val beforeTrack: TrackData,
        val afterTrack: TrackData
    ) : SessionCommand

    data class BatchMixChange(
        override val description: String,
        val beforeTracks: List<TrackData>,
        val afterTracks: List<TrackData>,
        val beforeMasterGainDb: Float,
        val afterMasterGainDb: Float
    ) : SessionCommand

    /** A small, fully reversible session-level edit such as a master preset. */
    data class ReplaceSession(
        override val description: String,
        val beforeSession: ProjectSession,
        val afterSession: ProjectSession
    ) : SessionCommand
}

/**
 * Robust Undo/Redo history manager with bounded history stack.
 */
class UndoManager(private val maxHistory: Int = 50) {
    private val undoStack = ArrayDeque<SessionCommand>()
    private val redoStack = ArrayDeque<SessionCommand>()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun getUndoTitle(): String = undoStack.lastOrNull()?.description ?: "Undo"
    fun getRedoTitle(): String = redoStack.lastOrNull()?.description ?: "Redo"

    fun pushAction(command: SessionCommand) {
        if (undoStack.size >= maxHistory) {
            undoStack.removeFirst()
        }
        undoStack.addLast(command)
        redoStack.clear()
    }

    fun undo(currentSession: ProjectSession): Pair<ProjectSession, SessionCommand>? {
        val command = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(command)

        val updatedSession = when (command) {
            is SessionCommand.EditClips -> {
                val newTracks = currentSession.tracks.map { trk ->
                    if (trk.index == command.trackIndex) trk.copy(clips = command.beforeClips) else trk
                }
                currentSession.copy(tracks = newTracks, revision = currentSession.revision + 1)
            }
            is SessionCommand.UpdateTrackParams -> {
                val newTracks = currentSession.tracks.map { trk ->
                    if (trk.index == command.trackIndex) command.beforeTrack else trk
                }
                currentSession.copy(tracks = newTracks, revision = currentSession.revision + 1)
            }
            is SessionCommand.BatchMixChange -> {
                currentSession.copy(
                    tracks = command.beforeTracks,
                    masterGainDb = command.beforeMasterGainDb,
                    revision = currentSession.revision + 1
                )
            }
            is SessionCommand.ReplaceSession -> command.beforeSession.copy(
                revision = currentSession.revision + 1
            )
        }
        return Pair(updatedSession, command)
    }

    fun redo(currentSession: ProjectSession): Pair<ProjectSession, SessionCommand>? {
        val command = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(command)

        val updatedSession = when (command) {
            is SessionCommand.EditClips -> {
                val newTracks = currentSession.tracks.map { trk ->
                    if (trk.index == command.trackIndex) trk.copy(clips = command.afterClips) else trk
                }
                currentSession.copy(tracks = newTracks, revision = currentSession.revision + 1)
            }
            is SessionCommand.UpdateTrackParams -> {
                val newTracks = currentSession.tracks.map { trk ->
                    if (trk.index == command.trackIndex) command.afterTrack else trk
                }
                currentSession.copy(tracks = newTracks, revision = currentSession.revision + 1)
            }
            is SessionCommand.BatchMixChange -> {
                currentSession.copy(
                    tracks = command.afterTracks,
                    masterGainDb = command.afterMasterGainDb,
                    revision = currentSession.revision + 1
                )
            }
            is SessionCommand.ReplaceSession -> command.afterSession.copy(
                revision = currentSession.revision + 1
            )
        }
        return Pair(updatedSession, command)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

/** One named project on disk, newest first in the Open dialog. */
data class SavedProject(
    val file: File,
    val name: String,
    val modifiedAt: Long,
)

/**
 * Handles versioned JSON project serialization, loading, and crash-recovery journal.
 */
class ProjectPersistence(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(ProjectSession::class.java).indent("  ")

    private val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
    private val journalFile = File(context.filesDir, "recovery_journal.json")
    private val lastProjectFile = File(projectsDir, LAST_PROJECT_NAME)

    /**
     * Saves a named project and refreshes the last-session snapshot used on the
     * next launch. The crash journal is cleared because this is a clean save.
     */
    fun saveProject(session: ProjectSession, fileName: String = defaultFileName(session)): File {
        val file = File(projectsDir, safeFileName(fileName))
        val json = adapter.toJson(session)
        writeAtomically(file, json)
        writeAtomically(lastProjectFile, json)
        clearCrashRecoveryJournal()
        return file
    }

    /** Periodic auto-save and the startup snapshot; never creates a named file. */
    fun saveLastSession(session: ProjectSession): File {
        writeAtomically(lastProjectFile, adapter.toJson(session))
        clearCrashRecoveryJournal()
        return lastProjectFile
    }

    fun loadLastSession(): ProjectSession? = loadProject(lastProjectFile)

    fun hasLastSession(): Boolean = lastProjectFile.exists() && lastProjectFile.length() > 10

    fun clearCrashRecoveryJournal() {
        if (journalFile.exists()) journalFile.delete()
    }

    fun listSavedProjects(): List<SavedProject> {
        val files = projectsDir.listFiles { f ->
            f.isFile && f.extension == "json" && f.name != LAST_PROJECT_NAME && !f.name.endsWith(".tmp")
        } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                SavedProject(
                    file = file,
                    name = file.nameWithoutExtension.replace('_', ' '),
                    modifiedAt = file.lastModified(),
                )
            }
    }

    fun writeCrashRecoveryJournal(session: ProjectSession) {
        try {
            writeAtomically(journalFile, adapter.toJson(session))
        } catch (_: Exception) {
            // The journal is best-effort: a failed write must never break editing.
        }
    }

    /**
     * Writes through a temp file and renames it into place, so an interrupted or
     * crashed write can never leave a truncated session that fails to load.
     */
    private fun writeAtomically(target: File, contents: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        val backup = File(target.parentFile, "${target.name}.bak")
        try {
            temp.writeText(contents)
            if (!temp.renameTo(target)) {
                // Some filesystems refuse to replace an existing file on rename.
                // Move the old file aside instead of writing the live file in
                // place, then restore it if the second rename also fails.
                if (target.exists() && !target.renameTo(backup)) {
                    throw java.io.IOException("Could not replace ${target.name}")
                }
                if (!temp.renameTo(target)) {
                    backup.renameTo(target)
                    throw java.io.IOException("Could not replace ${target.name}")
                }
                backup.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    fun hasCrashRecoveryJournal(): Boolean {
        return journalFile.exists() && journalFile.length() > 10
    }

    fun restoreFromCrashRecoveryJournal(): ProjectSession? {
        return try {
            if (!journalFile.exists()) return null
            val json = journalFile.readText()
            adapter.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun loadProject(file: File): ProjectSession? {
        return try {
            if (!file.exists()) return null
            val json = file.readText()
            adapter.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun defaultFileName(session: ProjectSession): String =
        "session_${session.projectName.replace(' ', '_')}.json"

    /** Keeps a project name from creating a path or an unopenable file. */
    private fun safeFileName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .ifBlank { "Untitled Project" }
        return if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
    }

    companion object {
        const val LAST_PROJECT_NAME = "last_project.json"
    }
}
