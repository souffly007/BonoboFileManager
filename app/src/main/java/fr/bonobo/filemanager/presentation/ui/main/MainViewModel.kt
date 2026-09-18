package fr.bonobo.filemanager.presentation.ui.main

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import fr.bonobo.filemanager.domain.usecase.CompressFileUseCase
import fr.bonobo.filemanager.domain.usecase.CopyFileUseCase
import fr.bonobo.filemanager.domain.usecase.DecompressFileUseCase
import fr.bonobo.filemanager.domain.usecase.DeleteFileUseCase
import fr.bonobo.filemanager.domain.usecase.GetFilesUseCase
import fr.bonobo.filemanager.domain.usecase.GetStorageInfoUseCase
import fr.bonobo.filemanager.domain.usecase.MoveFileUseCase
import fr.bonobo.filemanager.domain.usecase.RenameFileUseCase
import fr.bonobo.filemanager.domain.usecase.SearchFilesUseCase
import androidx.datastore.preferences.core.edit
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getFilesUseCase: GetFilesUseCase,
    private val searchFilesUseCase: SearchFilesUseCase,
    private val getStorageInfoUseCase: GetStorageInfoUseCase,
    private val compressFileUseCase: CompressFileUseCase,
    private val decompressFileUseCase: DecompressFileUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val renameFileUseCase: RenameFileUseCase,
    private val copyFileUseCase: CopyFileUseCase,
    private val moveFileUseCase: MoveFileUseCase,
    private val repository: IFileRepository,
    private val appRepository: fr.bonobo.filemanager.domain.repository.IAppRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val rootPath =
        Environment.getExternalStorageDirectory().absolutePath

    private val _uiState = MutableStateFlow(
        MainUiState(
            rootPath = rootPath,
            currentPath = rootPath
        ),
    )

    val uiState: StateFlow<MainUiState> =
        _uiState.asStateFlow()

    private val primaryLoadToken = java.util.concurrent.atomic.AtomicLong()
    private val secondaryLoadToken = java.util.concurrent.atomic.AtomicLong()
    private var favoriteRoot: String? = null

    init {
        loadSettings()
        loadStorageInfo()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            context.settingsDataStore.data.collect { preferences ->
                val sortModeName = preferences[SettingsKeys.SORT_MODE] ?: SortMode.NAME.name
                val isAscending = preferences[SettingsKeys.IS_SORT_ASCENDING] ?: true
                val thumbSize = preferences[SettingsKeys.THUMBNAIL_SIZE] ?: "MEDIUM"
                
                val mode = try {
                    SortMode.valueOf(sortModeName)
                } catch (_: Exception) {
                    SortMode.NAME
                }

                _uiState.update { it.copy(
                    sortMode = mode,
                    isSortAscending = isAscending,
                    thumbnailSize = thumbSize
                ) }
                // On rafraîchit la liste avec le nouveau tri
                val currentFiles = _uiState.value.files
                if (currentFiles.isNotEmpty()) {
                    _uiState.update { it.copy(files = sortFiles(currentFiles, mode, isAscending)) }
                } else {
                    loadFiles()
                }
            }
        }
    }

    private fun saveSettings(mode: SortMode, ascending: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.SORT_MODE] = mode.name
                preferences[SettingsKeys.IS_SORT_ASCENDING] = ascending
            }
        }
    }

    fun loadFiles(path: String = if (_uiState.value.activePanel == 1) _uiState.value.currentPath else _uiState.value.secondPath ?: _uiState.value.rootPath) {
        val panel = _uiState.value.activePanel
        val category = _uiState.value.categoryName
        if (panel == 1 && category != null && path == _uiState.value.currentPath) {
            loadCategory(category, _uiState.value.searchQuery)
            return
        }
        val counter = if (panel == 1) primaryLoadToken else secondaryLoadToken
        val token = counter.incrementAndGet()
        _uiState.update {
            if (panel == 1) it.copy(currentPath = path, categoryName = null, files = emptyList(), isLoading = true, error = null)
            else it.copy(secondPath = path, secondFiles = emptyList(), isSecondLoading = true, error = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getFilesUseCase(path) }.onSuccess { files ->
                _uiState.update { state ->
                    if (token != counter.get()) state else {
                        val sorted = sortFiles(files, state.sortMode, state.isSortAscending)
                        if (panel == 1) state.copy(files = sorted, isLoading = false)
                        else state.copy(secondFiles = sorted, isSecondLoading = false)
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    if (token != counter.get()) state else if (panel == 1)
                        state.copy(isLoading = false, error = error.message ?: "Lecture impossible")
                    else state.copy(isSecondLoading = false, error = error.message ?: "Lecture impossible")
                }
            }
        }
    }

    fun openFolder(path: String, keepFavoriteOrigin: Boolean = false) {
        favoriteRoot = if (keepFavoriteOrigin) favoriteRoot ?: path else null
        _uiState.update { it.copy(categoryName = null, searchQuery = "") }
        loadFiles(path)
    }

    private fun sortFiles(files: List<FileItem>, mode: SortMode, ascending: Boolean): List<FileItem> {
        return when (mode) {
            SortMode.NAME -> files.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }.then { item1, item2 ->
                    if (ascending) item1.name.lowercase().compareTo(item2.name.lowercase())
                    else item2.name.lowercase().compareTo(item1.name.lowercase())
                }
            )
            SortMode.SIZE -> files.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }.then { item1, item2 ->
                    if (ascending) item1.size.compareTo(item2.size)
                    else item2.size.compareTo(item1.size)
                }
            )
            SortMode.DATE -> files.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }.then { item1, item2 ->
                    if (ascending) item1.lastModified.compareTo(item2.lastModified)
                    else item2.lastModified.compareTo(item1.lastModified)
                }
            )
        }
    }

    fun setSortMode(mode: SortMode) {
        val currentMode = _uiState.value.sortMode
        val currentAscending = _uiState.value.isSortAscending
        
        if (currentMode == mode) {
            // Inverser le sens si c'est le même mode
            val newAscending = !currentAscending
            saveSettings(mode, newAscending)
        } else {
            // Nouveau mode
            val defaultAscending = when(mode) {
                SortMode.DATE -> false 
                else -> true
            }
            saveSettings(mode, defaultAscending)
        }
    }

    fun toggleViewMode() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun toggleDualPane() {
        if (_uiState.value.categoryName == "Favoris") return
        _uiState.update { state ->
            val newIsDualPane = !state.isDualPane
            state.copy(
                isDualPane = newIsDualPane,
                secondPath = if (newIsDualPane) state.currentPath else null,
                activePanel = 1
            )
        }
        if (_uiState.value.isDualPane) {
            loadFiles(_uiState.value.currentPath)
        }
    }

    fun setActivePanel(panel: Int) {
        _uiState.update { it.copy(activePanel = panel) }
    }

    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedPaths.contains(path)) {
                state.selectedPaths - path
            } else {
                state.selectedPaths + path
            }
            state.copy(selectedPaths = newSelection)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun deleteSelected() {
        val paths = _uiState.value.selectedPaths
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            paths.forEach { path ->
                deleteFileUseCase(path)
            }
            clearSelection()
            loadFiles()
        }
    }

    fun multiRename(
        prefix: String = "",
        suffix: String = "",
        find: String = "",
        replace: String = "",
        startCounter: Int? = null
    ) {
        val paths = _uiState.value.selectedPaths.toList().sorted()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            
            paths.forEachIndexed { index, path ->
                val file = File(path)
                val ext = file.extension
                val baseName = file.nameWithoutExtension
                
                var newName = baseName
                if (find.isNotEmpty()) {
                    newName = newName.replace(find, replace)
                }
                
                newName = prefix + newName + suffix
                
                if (startCounter != null) {
                    val count = (startCounter + index).toString().padStart(2, '0')
                    newName = "${count}_$newName"
                }
                
                if (ext.isNotEmpty()) {
                    newName = "$newName.$ext"
                }
                
                renameFileUseCase(path, newName)
            }
            
            clearSelection()
            loadFiles()
        }
    }

    fun moveToTrashSelected() {
        val paths = _uiState.value.selectedPaths
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            paths.forEach { path ->
                repository.moveToTrash(path)
            }
            clearSelection()
            loadFiles()
        }
    }

    fun loadCategory(type: String, query: String = "") {
        val token = primaryLoadToken.incrementAndGet()
        favoriteRoot = null
        _uiState.update { it.copy(categoryName = type, searchQuery = query,
            files = emptyList(), selectedPaths = emptySet(), activePanel = 1,
            isDualPane = if (type == "Favoris") false else it.isDualPane,
            isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (type) {
                    "Images" -> repository.getFilesByType("image/")
                    "Vidéos" -> repository.getFilesByType("video/")
                    "Musique" -> repository.getFilesByType("audio/")
                    "Documents" -> repository.getFilesByType("application/")
                    "Téléchargements" -> fr.bonobo.filemanager.util.FileUtils.listFiles(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    "Gros fichiers" -> repository.getLargeFiles(100 * 1024 * 1024)
                    "Corbeille" -> repository.getTrashFiles()
                    "Coffre-fort" -> repository.getVaultFiles()
                    "Favoris" -> fr.bonobo.filemanager.domain.model.FavoriteFolders.select(repository.observeFavorites().first(), query)
                    else -> emptyList()
                }
            }.onSuccess { files ->
                _uiState.update { state ->
                    if (token != primaryLoadToken.get()) state else state.copy(
                        files = sortFiles(files, state.sortMode, state.isSortAscending), isLoading = false)
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    if (token != primaryLoadToken.get()) state else state.copy(
                        isLoading = false, error = error.message ?: "Chargement impossible")
                }
            }
        }
    }

    fun refresh() {
        val category = _uiState.value.categoryName
        if (category != null) {
            loadCategory(category, _uiState.value.searchQuery)
        } else {
            loadFiles()
        }
        loadStorageInfo()
    }

    suspend fun importFileToVault(uri: String): Result<String> {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val result = repository.importFileToVault(uri)
        if (result.isSuccess) {
            loadCategory("Coffre-fort")
        } else {
            _uiState.update {
                it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Échec de l'importation")
            }
        }
        return result
    }

    private fun loadStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getStorageInfoUseCase()
            }.onSuccess { storageInfo ->
                _uiState.update { it.copy(
                    storageInfo = storageInfo
                ) }
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    error = exception.message
                        ?: "Impossible de récupérer le stockage"
                ) }
            }
        }
    }

    fun open(item: FileItem) {
        if (item.isDirectory) {
            openFolder(item.path, _uiState.value.categoryName == "Favoris" || favoriteRoot != null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.addToHistory(item)
            }
        }
    }

    fun search(query: String) {
        if (_uiState.value.categoryName == "Favoris") {
            loadCategory("Favoris", query)
            return
        }
        val searchToken = primaryLoadToken.incrementAndGet()
        _uiState.update { it.copy(searchQuery = query) }

        if (query.isBlank()) {
            loadFiles()
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                searchFilesUseCase(query)
            }.onSuccess { files ->
                _uiState.update { if (searchToken != primaryLoadToken.get()) it else it.copy(
                    files = files,
                    isLoading = false
                ) }
            }.onFailure { exception ->
                _uiState.update { if (searchToken != primaryLoadToken.get()) it else it.copy(
                    isLoading = false,
                    error = exception.message
                        ?: "Erreur lors de la recherche"
                ) }
            }
        }
    }

    fun toggleFavorite(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.toggleFavorite(item)
            }.onSuccess {
                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    error = exception.message
                        ?: "Impossible de modifier le favori"
                ) }
            }
        }
    }

    fun rename(item: FileItem, newName: String) {
        val cleanName = newName.trim()

        if (cleanName.isBlank()) {
            _uiState.update { it.copy(
                error = "Le nom ne peut pas être vide"
            ) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                renameFileUseCase(item.path, cleanName)
            }.onSuccess {
                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    error = exception.message
                        ?: "Impossible de renommer le fichier"
                ) }
            }
        }
    }

    fun delete(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                deleteFileUseCase(item.path)
            }.onSuccess {
                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    error = exception.message
                        ?: "Impossible de supprimer le fichier"
                ) }
            }
        }
    }

    fun compress(item: FileItem, zipName: String, password: String? = null) {
        val source = File(item.path)
        val normalizedName = zipName.trim()
            .removeSuffix(".zip")
            .removeSuffix(".ZIP")

        if (normalizedName.isBlank()) {
            _uiState.update { it.copy(
                error = "Le nom de l'archive ne peut pas être vide"
            ) }
            return
        }

        val parentDirectory = source.parentFile

        if (parentDirectory == null) {
            _uiState.update { it.copy(
                error = "Dossier parent introuvable"
            ) }
            return
        }

        val destination = File(
            parentDirectory,
            "$normalizedName.zip"
        )

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                isLoading = true,
                error = null
            ) }

            compressFileUseCase(
                sources = listOf(source.absolutePath),
                outputZip = destination.absolutePath,
                password = password?.takeIf { it.isNotBlank() }
            ).onSuccess {
                _uiState.update { it.copy(
                    isLoading = false
                ) }
                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    isLoading = false,
                    error = exception.message
                        ?: "Impossible de compresser le fichier"
                ) }
            }
        }
    }

    fun setPendingAppBackup(app: fr.bonobo.filemanager.domain.model.AppItem) {
        _uiState.update { it.copy(pendingAppBackup = app) }
    }

    fun clearPendingAppBackup() {
        _uiState.update { it.copy(pendingAppBackup = null) }
    }

    fun backupAppToCurrentPath() {
        val app = _uiState.value.pendingAppBackup ?: return
        val destination = _uiState.value.currentPath
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            appRepository.backupApp(app, destination)
                .onSuccess { 
                    clearPendingAppBackup()
                    loadFiles() 
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Échec backup: ${e.message}") }
                }
        }
    }

    fun setPendingExtraction(item: FileItem) {
        _uiState.update { it.copy(pendingExtraction = item) }
    }

    fun clearPendingExtraction() {
        _uiState.update { it.copy(pendingExtraction = null) }
    }

    fun extractToCurrentPath(password: String? = null) {
        val zipFileItem = _uiState.value.pendingExtraction ?: return
        val zipFile = File(zipFileItem.path)
        val destinationDirectory = File(_uiState.value.currentPath)

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                isLoading = true,
                error = null
            ) }

            decompressFileUseCase(
                zipFile = zipFile,
                destDir = destinationDirectory,
                password = password?.takeIf { it.isNotBlank() }
            ).onSuccess {
                clearPendingExtraction()
                _uiState.update { it.copy(isLoading = false) }
                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    isLoading = false,
                    error = exception.message ?: "Erreur de décompression"
                ) }
            }
        }
    }

    fun decompress(item: FileItem) {
        // Cette méthode peut être conservée pour une extraction rapide au même endroit
        // Mais nous privilégions maintenant setPendingExtraction
        setPendingExtraction(item)
    }

    fun setClipboard(item: FileItem, isMove: Boolean) {
        _uiState.update { it.copy(
            clipboard = ClipboardState(
                file = item,
                isMove = isMove
            )
        ) }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    fun pasteClipboard() {
        val clipboardState = _uiState.value.clipboard
            ?: return

        val sourceFile = File(clipboardState.file.path)
        val destinationDirectory = File(_uiState.value.currentPath)
        val destinationFile = File(
            destinationDirectory,
            sourceFile.name
        )

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                isLoading = true,
                error = null
            ) }

            runCatching {
                if (clipboardState.isMove) {
                    moveFileUseCase(
                        sourceFile.absolutePath,
                        destinationFile.absolutePath
                    )
                } else {
                    copyFileUseCase(
                        sourceFile.absolutePath,
                        destinationFile.absolutePath
                    )
                }
            }.onSuccess {
                clearClipboard()

                _uiState.update { it.copy(
                    isLoading = false
                ) }

                loadFiles()
            }.onFailure { exception ->
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "Erreur lors de l'opération : ${
                        exception.message ?: "erreur inconnue"
                    }"
                ) }
            }
        }
    }


    fun goUp() {
        if (favoriteRoot != null && _uiState.value.currentPath == favoriteRoot) {
            loadCategory("Favoris")
            return
        }
        if (_uiState.value.categoryName != null) return

        val current = File(_uiState.value.currentPath)
        val parent = current.parentFile

        // On ne remonte pas au-dessus de la racine définie (ex: /storage/emulated/0)
        if (parent != null && current.absolutePath != rootPath) {
            loadFiles(parent.absolutePath)
        }
    }

    fun isAtRoot(): Boolean {
        return _uiState.value.categoryName != null || (favoriteRoot == null && _uiState.value.currentPath == rootPath)
    }

    fun resetToLocalRoot() {
        favoriteRoot = null
        _uiState.update { it.copy(
            currentPath = rootPath,
            categoryName = null,
            searchQuery = "",
            error = null
        ) }
        loadFiles(rootPath)
    }

    fun createFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createFolder(_uiState.value.currentPath, name)
                .onSuccess { loadFiles() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createFile(_uiState.value.currentPath, name)
                .onSuccess { loadFiles() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun moveToTrash(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveToTrash(item.path)
                .onSuccess { loadFiles() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun restoreFromTrash(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.restoreFromTrash(item)
                .onSuccess { loadCategory("Corbeille") }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.emptyTrash()
                .onSuccess { loadCategory("Corbeille") }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun encryptFile(item: FileItem, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                fr.bonobo.filemanager.util.SecurityUtils.encrypt(File(item.path), password)
            }.onSuccess { 
                loadFiles()
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = "Erreur de cryptage: ${e.message}") }
            }
        }
    }

    fun decryptFile(item: FileItem, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                fr.bonobo.filemanager.util.SecurityUtils.decrypt(File(item.path), password)
            }.onSuccess { 
                loadFiles()
            }.onFailure { _ ->
                _uiState.update { it.copy(isLoading = false, error = "Mot de passe incorrect ou fichier corrompu") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun addBookmark() {
        viewModelScope.launch {
            val currentPath = _uiState.value.currentPath
            val name = currentPath.substringAfterLast("/")
            repository.addBookmark(currentPath, name)
        }
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch {
            repository.removeBookmark(path)
        }
    }

    val bookmarks = repository.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites = repository.observeFavorites()
        .map { fr.bonobo.filemanager.domain.model.FavoriteFolders.select(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
