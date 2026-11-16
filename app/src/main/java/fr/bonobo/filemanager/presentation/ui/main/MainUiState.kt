package fr.bonobo.filemanager.presentation.ui.main

import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.StorageInfo

data class MainUiState(
    val rootPath: String = "",
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val searchQuery: String = "",
    val storageInfo: StorageInfo? = null,
    val clipboard: ClipboardState? = null,
    val operationNotice: String? = null,
    val pendingRestore: FileItem? = null,
    val pendingRestores: List<FileItem> = emptyList(),
    val deletionBusy: Boolean = false,
    val pendingArtifacts: List<fr.bonobo.filemanager.util.OperationArtifacts.Entry> = emptyList(),
    val conflictPrompt: ConflictPrompt? = null,
    /** Trash entries that no longer match their previous restore receipt (with the reason). */
    val restoreMismatches: List<Pair<FileItem, String>> = emptyList(),
    val restoreMismatchParent: String? = null,
    val inactiveSelectedPaths: Set<String> = emptySet(),
    val transferProgress: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val secondError: String? = null,
    
    // Nouvelles fonctionnalités
    val sortMode: SortMode = SortMode.NAME,
    val isSortAscending: Boolean = true,
    val isGridView: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val pendingExtraction: FileItem? = null,
    val pendingAppBackup: fr.bonobo.filemanager.domain.model.AppItem? = null,
    val categoryName: String? = null,
    val thumbnailSize: String = "MEDIUM",
    val isDualPane: Boolean = false,
    val secondPath: String? = null,
    val secondFiles: List<FileItem> = emptyList(),
    val isSecondLoading: Boolean = false,
    val activePanel: Int = 1 // 1 or 2
) {
    fun activate(panel: Int): MainUiState = if (panel == activePanel) this else copy(
        activePanel = panel, selectedPaths = inactiveSelectedPaths, inactiveSelectedPaths = selectedPaths)
}

/** Destination conflicts found before starting a paste, resolved one by one (or all at once). */
data class ConflictPrompt(
    val clipboard: ClipboardState,
    val destination: String,
    val conflicts: List<FileItem>,
    val index: Int = 0,
    val decisions: Map<String, fr.bonobo.filemanager.domain.transfer.ConflictAction> = emptyMap(),
    val sameFolder: Boolean = false,
    val compareHash: Boolean = false,
    /** Element already present in the destination, by name (size and date shown in the dialog). */
    val existing: Map<String, FileItem> = emptyMap(),
    /** Source path → same SHA-256 as the present file (computed in background for files of equal size). */
    val identical: Map<String, Boolean> = emptyMap()
) {
    val current: FileItem get() = conflicts[index]
    val currentExisting: FileItem? get() = existing[current.name]
    val remaining: Int get() = conflicts.size - index
}

enum class SortMode {
    NAME, SIZE, DATE
}
