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
    val isLoading: Boolean = false,
    val error: String? = null,
    
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
)

enum class SortMode {
    NAME, SIZE, DATE
}
