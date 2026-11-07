package com.ncstudio.daw.ui

import java.io.File

enum class StatusKind { INFO, SUCCESS, ERROR }

data class StatusAction(val label: String, val forMessage: String? = null)

enum class ProjectDialogMode { OPEN, SAVE_AS }

data class ProjectFileInfo(
    val file: File,
    val name: String,
    val modifiedAt: Long,
)

data class ProjectUiState(
    val currentName: String = "Untitled Project",
    val currentPath: String? = null,
    val savedProjects: List<ProjectFileInfo> = emptyList(),
    val showDialog: Boolean = false,
    val dialogMode: ProjectDialogMode = ProjectDialogMode.OPEN,
    val lastSavedAt: Long = 0L,
    val isSaving: Boolean = false,
)
