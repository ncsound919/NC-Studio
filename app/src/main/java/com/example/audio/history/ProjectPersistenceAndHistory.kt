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
        }
        return Pair(updatedSession, command)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

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

    fun saveProject(session: ProjectSession, fileName: String = "session_${session.projectName.replace(" ", "_")}.json"): File {
        val file = File(projectsDir, fileName)
        val json = adapter.toJson(session)
        file.writeText(json)
        // Also clear recovery journal since cleanly saved
        if (journalFile.exists()) journalFile.delete()
        return file
    }

    fun writeCrashRecoveryJournal(session: ProjectSession) {
        try {
            val json = adapter.toJson(session)
            journalFile.writeText(json)
        } catch (_: Exception) {}
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

    fun listSavedProjects(): List<File> {
        return projectsDir.listFiles { f -> f.extension == "json" }?.toList() ?: emptyList()
    }
}
