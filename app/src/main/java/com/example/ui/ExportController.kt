package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.example.audio.engine.ExportFormat
import com.example.audio.engine.OfflineRenderer
import com.example.audio.model.ProjectSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Master and stem export: renders the live session, then copies the result into
 * the folder the user picked. Render state lives in [EngineUiState] so the
 * export dialog has one source of truth.
 */
class ExportController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<EngineUiState>,
    private val currentSession: () -> ProjectSession,
    private val trackBuffers: () -> Array<FloatArray>,
    private val prefs: SharedPreferences,
) {

    private var exportTreeUri: Uri? = prefs.getString(PREF_TREE_URI, null)?.let(Uri::parse)

    fun showDialog(show: Boolean) {
        uiState.update {
            it.copy(
                showExportDialog = show,
                exportProgress = 0f,
                exportedFile = null,
                exportedStemFiles = emptyList(),
                exportError = null,
            )
        }
    }

    fun setDestination(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers do not support persistable grants; the export still works for this session.
        }
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Chosen folder"
        exportTreeUri = uri
        prefs.edit {
            putString(PREF_TREE_URI, uri.toString())
            putString(PREF_TREE_NAME, name)
        }
        uiState.update {
            it.copy(
                exportDestinationName = name,
                statusMessage = "Export destination set to $name.",
                statusKind = StatusKind.SUCCESS,
            )
        }
    }

    /** Publishes rendered app files into the user's chosen document tree. */
    private fun publishExports(files: List<File>): String? {
        val tree = exportTreeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        if (!tree.canWrite()) return null
        var copied = 0
        for (file in files) {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "audio/*"
            val target = tree.createFile(mime, file.name) ?: continue
            try {
                context.contentResolver
                    .openOutputStream(target.uri)
                    ?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                copied++
            } catch (_: Exception) {
                // Keep the app-local copy; the share sheet still works.
            }
        }
        return if (copied > 0) tree.name else null
    }

    fun renderSessionToAudio(
        format: ExportFormat = ExportFormat.WAV_24BIT,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        scope.launch(dispatcher) {
            try {
                uiState.update {
                    it.copy(
                        exportProgress = 0.05f,
                        exportedFile = null,
                        exportedStemFiles = emptyList(),
                        exportError = null,
                        statusMessage = "Rendering session to ${format.displayName}...",
                    )
                }
                val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                val outFile = File(exportDir, "NCStudio_${System.currentTimeMillis()}${format.extension}")
                val onProgress: (Float) -> Unit = { p ->
                    uiState.update { it.copy(exportProgress = p) }
                }

                val renderedFile: File? = when (format) {
                    ExportFormat.AAC_320KBPS -> OfflineRenderer.renderSessionToAac(
                        project = currentSession(),
                        trackBuffers = trackBuffers(),
                        outputFile = outFile,
                        onProgress = onProgress,
                    )
                    ExportFormat.MP3_320KBPS -> OfflineRenderer.renderSessionToMp3(
                        project = currentSession(),
                        trackBuffers = trackBuffers(),
                        outputFile = outFile,
                        onProgress = onProgress,
                    )
                    ExportFormat.FLAC_LOSSLESS -> OfflineRenderer.renderSessionToFlac(
                        project = currentSession(),
                        trackBuffers = trackBuffers(),
                        outputFile = outFile,
                        onProgress = onProgress,
                    )
                    else -> {
                        val wrote = OfflineRenderer.renderSessionToWav(
                            project = currentSession(),
                            trackBuffers = trackBuffers(),
                            outputFile = outFile,
                            onProgress = onProgress,
                        )
                        if (wrote) outFile else null
                    }
                }

                if (renderedFile != null && renderedFile.exists()) {
                    val usedWavFallback = ".${renderedFile.extension}" != format.extension
                    val publishedTo = publishExports(listOf(renderedFile))
                    val destinationSuffix = publishedTo?.let { " • saved to $it" } ?: " • saved to app storage"
                    uiState.update {
                        it.copy(
                            exportProgress = 1.0f,
                            exportedFile = renderedFile,
                            exportError = null,
                            statusMessage = if (usedWavFallback) {
                                "Export complete: ${renderedFile.name} (${format.displayName} encoder unavailable; WAV fallback, ${renderedFile.length() / 1024} KB)$destinationSuffix"
                            } else {
                                "Export complete: ${renderedFile.name} (${renderedFile.length() / 1024} KB)$destinationSuffix"
                            },
                            statusKind = StatusKind.SUCCESS,
                        )
                    }
                } else {
                    uiState.update {
                        it.copy(
                            exportProgress = 0f,
                            exportError = "Export failed. Check available storage and try again.",
                            statusMessage = "Export failed. Check available storage and try again.",
                            statusKind = StatusKind.ERROR,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                uiState.update {
                    it.copy(
                        exportProgress = 0f,
                        exportError = "Export failed: ${t.message ?: "unknown error"}",
                        statusMessage = "Export failed: ${t.message ?: "unknown error"}",
                        statusKind = StatusKind.ERROR,
                    )
                }
            }
        }
    }

    fun renderStems(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        scope.launch(dispatcher) {
            try {
                uiState.update {
                    it.copy(
                        exportProgress = 0.05f,
                        exportedFile = null,
                        exportedStemFiles = emptyList(),
                        exportError = null,
                        statusMessage = "Rendering stems...",
                    )
                }
                val exportDir = File(
                    context.filesDir,
                    "exports/stems_${System.currentTimeMillis()}",
                ).apply { mkdirs() }
                val files = OfflineRenderer.renderStemsToWav(
                    project = currentSession(),
                    trackBuffers = trackBuffers(),
                    outputDir = exportDir,
                    bitsPerSample = 24,
                    onStemProgress = { completed, total, trackName ->
                        uiState.update {
                            it.copy(
                                exportProgress = 0.05f + 0.9f * completed / total.coerceAtLeast(1),
                                statusMessage = "Rendering stem $completed/$total: $trackName",
                            )
                        }
                    },
                )
                if (files.isNotEmpty()) publishExports(files)
                uiState.update {
                    if (files.isEmpty()) {
                        it.copy(
                            exportProgress = 0f,
                            exportError = "Stem export failed. Check available storage and try again.",
                            statusMessage = "Stem export failed. Check available storage and try again.",
                            statusKind = StatusKind.ERROR,
                        )
                    } else {
                        it.copy(
                            exportProgress = 1.0f,
                            exportedFile = files.firstOrNull(),
                            exportedStemFiles = files,
                            exportError = null,
                            statusMessage = "Exported ${files.size} stems to ${it.exportDestinationName}",
                            statusKind = StatusKind.SUCCESS,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                uiState.update {
                    it.copy(
                        exportProgress = 0f,
                        exportError = "Stem export failed: ${t.message ?: "unknown error"}",
                        statusMessage = "Stem export failed: ${t.message ?: "unknown error"}",
                        statusKind = StatusKind.ERROR,
                    )
                }
            }
        }
    }

    private companion object {
        const val PREF_TREE_URI = "export_tree_uri"
        const val PREF_TREE_NAME = "export_tree_name"
    }
}
