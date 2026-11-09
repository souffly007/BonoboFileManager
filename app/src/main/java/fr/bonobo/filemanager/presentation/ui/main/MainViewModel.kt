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
import kotlinx.coroutines.ensureActive
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
    private val transferManager: fr.bonobo.filemanager.data.transfer.TransferManager,
    private val transferEngines: fr.bonobo.filemanager.data.transfer.TransferEngineFactory,
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
    private var clipboardParent: String? = null
    private val safParents = java.util.concurrent.ConcurrentHashMap<Pair<Int, String>, String>()
    private val navigationRoots = java.util.concurrent.ConcurrentHashMap<Int, String>().apply {
        put(1, rootPath)
        put(2, rootPath)
    }

    fun navigationPath(panel: Int = _uiState.value.activePanel): String =
        if (panel == 1) _uiState.value.currentPath else _uiState.value.secondPath ?: rootPath

    fun navigationRoot(panel: Int = _uiState.value.activePanel): String =
        navigationRoots[panel] ?: rootPath

    fun usbLocationLabel(panel: Int = _uiState.value.activePanel): String? {
        val path = navigationPath(panel)
        if (!fr.bonobo.filemanager.util.SafFiles.isSaf(path)) return null
        val uri = android.net.Uri.parse(path)
        val tree = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return "Emplacement USB autorisé"
        val volumes = context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
        val scope = (if (uri.authority == "com.android.externalstorage.documents") volumes else emptyList()).map { fr.bonobo.filemanager.util.UsbAccessScope.classify(tree, it.uuid) }
            .firstOrNull { it != fr.bonobo.filemanager.util.UsbAccessScope.Scope.UNKNOWN }
            ?: fr.bonobo.filemanager.util.UsbAccessScope.Scope.UNKNOWN
        val base = fr.bonobo.filemanager.util.UsbAccessScope.label(scope)
        val current = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrDefault(tree)
        return if (path == navigationRoot(panel)) "$base : $tree" else "$base : $tree — dossier affiché : $current"
    }

    private fun resolveNavigationRoot(path: String, previousRoot: String): String {
        if (fr.bonobo.filemanager.util.SafFiles.isSaf(path)) return fr.bonobo.filemanager.util.SafFiles.root(path)
        val current = File(path).toPath().toAbsolutePath().normalize()
        if (!fr.bonobo.filemanager.util.SafFiles.isSaf(previousRoot) && current.startsWith(File(previousRoot).toPath().toAbsolutePath().normalize())) return previousRoot
        val volumeRoots = if (android.os.Build.VERSION.SDK_INT >= 30)
            context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { it.directory?.absolutePath }
        else emptyList()
        val storageRoots = listOf(rootPath) + context.getExternalFilesDirs(null).mapNotNull { dir ->
            dir?.absolutePath?.substringBefore("/Android/data/${context.packageName}/files")
        } + volumeRoots
        return storageRoots.distinct().filter {
            current.startsWith(File(it).toPath().toAbsolutePath().normalize())
        }.maxByOrNull { it.length } ?: File.separator
    }
    private val folderPrefs = context.getSharedPreferences("folder_display_preferences", android.content.Context.MODE_PRIVATE)

    private fun folderKey(path: String, suffix: String) = "${path.hashCode()}_$suffix"

    private fun folderDisplayPrefs(path: String): Triple<SortMode, Boolean, Boolean> {
        val state = _uiState.value
        val mode = runCatching {
            SortMode.valueOf(folderPrefs.getString(folderKey(path, "sort"), null) ?: state.sortMode.name)
        }.getOrDefault(state.sortMode)
        val ascending = if (folderPrefs.contains(folderKey(path, "asc")))
            folderPrefs.getBoolean(folderKey(path, "asc"), state.isSortAscending) else state.isSortAscending
        val grid = if (folderPrefs.contains(folderKey(path, "grid")))
            folderPrefs.getBoolean(folderKey(path, "grid"), state.isGridView) else state.isGridView
        return Triple(mode, ascending, grid)
    }

    private fun saveFolderDisplayPrefs(path: String, mode: SortMode, ascending: Boolean, grid: Boolean = _uiState.value.isGridView) {
        folderPrefs.edit()
            .putString(folderKey(path, "sort"), mode.name)
            .putBoolean(folderKey(path, "asc"), ascending)
            .putBoolean(folderKey(path, "grid"), grid)
            .apply()
    }

    init {
        loadSettings()
        loadStorageInfo()
        viewModelScope.launch(Dispatchers.IO) {
            transferManager.state.collect { if (!it.active) refreshArtifacts() }
        }
    }

    private fun refreshArtifacts() {
        runCatching { fr.bonobo.filemanager.util.OperationArtifacts.pending(fr.bonobo.filemanager.util.SafFiles.artifactDirectory(context)) }
            .onSuccess { entries -> _uiState.update { it.copy(pendingArtifacts = entries) } }
            .onFailure { e -> _uiState.update { it.copy(error = "Lecture du suivi des fichiers partiels impossible : ${e.message}") } }
    }

    fun examineArtifact(entry: fr.bonobo.filemanager.util.OperationArtifacts.Entry) {
        if (transferBusy()) return
        openFolder(entry.parent, resetNavigationRoot = true)
    }

    fun acknowledgeArtifact(entry: fr.bonobo.filemanager.util.OperationArtifacts.Entry) {
        if (transferBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { fr.bonobo.filemanager.util.OperationArtifacts.acknowledge(
                fr.bonobo.filemanager.util.SafFiles.artifactDirectory(context), entry) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            refreshArtifacts()
        }
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

    fun loadFiles(path: String = navigationPath()) {
        loadFilesForPanel(path, _uiState.value.activePanel)
    }

    private fun loadFilesForPanel(path: String, panel: Int) {
        val previous = navigationPath(panel)
        if (fr.bonobo.filemanager.util.SafFiles.isSaf(path) && fr.bonobo.filemanager.util.SafFiles.isSaf(previous) && path != previous && fr.bonobo.filemanager.util.SafFiles.root(path) == fr.bonobo.filemanager.util.SafFiles.root(previous) && !safParents.containsKey(panel to path) && path != fr.bonobo.filemanager.util.SafFiles.root(path)) safParents[panel to path] = previous
        val category = _uiState.value.categoryName
        if (panel == 1 && category != null && path == _uiState.value.currentPath) {
            loadCategory(category, _uiState.value.searchQuery)
            return
        }
        val counter = if (panel == 1) primaryLoadToken else secondaryLoadToken
        val token = counter.incrementAndGet()
        navigationRoots[panel] = resolveNavigationRoot(path, navigationRoot(panel))
        val isRootOpen = path == navigationRoot(panel)
        val storage = if (fr.bonobo.filemanager.util.SafFiles.isSaf(path))
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SAF
            else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.LOCAL
        if (isRootOpen) fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_ROOT,
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.START,
            storage = storage, kind = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.DIRECTORY,
            phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.OPEN)
        val (folderSort, folderAscending, folderGrid) = folderDisplayPrefs(path)
        _uiState.update {
            if (panel == 1) it.copy(currentPath = path, categoryName = null, files = emptyList(), isLoading = true, error = null,
                sortMode = folderSort, isSortAscending = folderAscending, isGridView = folderGrid,
                selectedPaths = if (path != previous && panel == it.activePanel) emptySet() else it.selectedPaths,
                inactiveSelectedPaths = if (path != previous && panel != it.activePanel) emptySet() else it.inactiveSelectedPaths)
            else it.copy(secondPath = path, secondFiles = emptyList(), isSecondLoading = true, secondError = null,
                selectedPaths = if (path != previous && panel == it.activePanel) emptySet() else it.selectedPaths,
                inactiveSelectedPaths = if (path != previous && panel != it.activePanel) emptySet() else it.inactiveSelectedPaths)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getFilesUseCase(path) }.onSuccess { files ->
                if (isRootOpen) fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_ROOT,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK,
                    storage = storage, kind = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.DIRECTORY,
                    phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.READ)
                _uiState.update { state ->
                    if (token != counter.get()) state else {
                        val sorted = sortFiles(files, if (panel == 1) folderSort else state.sortMode, if (panel == 1) folderAscending else state.isSortAscending)
                        if (panel == 1) state.copy(files = sorted, isLoading = false)
                        else state.copy(secondFiles = sorted, isSecondLoading = false, secondError = null)
                    }
                }
            }.onFailure { error ->
                if (isRootOpen) fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_ROOT,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.ERROR,
                    storage = storage, kind = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.DIRECTORY,
                    phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.READ)
                _uiState.update { state ->
                    if (token != counter.get()) state else if (panel == 1)
                        state.copy(isLoading = false, error = error.message ?: "Lecture impossible")
                    else state.copy(isSecondLoading = false, secondError = error.message ?: "Lecture impossible")
                }
            }
        }
    }

    fun openFolder(path: String, keepFavoriteOrigin: Boolean = false, resetNavigationRoot: Boolean = false) {
        if (fr.bonobo.filemanager.util.SafFiles.isSaf(path) && !resetNavigationRoot && path != navigationPath()) safParents[_uiState.value.activePanel to path] = navigationPath()
        if (resetNavigationRoot) {
            navigationRoots[_uiState.value.activePanel] = resolveNavigationRoot(path, rootPath)
        }
        favoriteRoot = if (keepFavoriteOrigin) favoriteRoot ?: path else null
        _uiState.update { it.copy(categoryName = if (it.activePanel == 1) null else it.categoryName, searchQuery = "") }
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
        val state = _uiState.value
        val newAscending = if (state.sortMode == mode) !state.isSortAscending else mode != SortMode.DATE
        val sorted = sortFiles(state.files, mode, newAscending)
        val secondSorted = sortFiles(state.secondFiles, mode, newAscending)
        _uiState.update { it.copy(sortMode = mode, isSortAscending = newAscending, files = sorted, secondFiles = secondSorted) }
        saveSettings(mode, newAscending) // sert aussi de valeur par défaut pour les nouveaux dossiers
        if (state.categoryName == null) saveFolderDisplayPrefs(navigationPath(), mode, newAscending)
    }

    fun toggleViewMode() {
        val state = _uiState.value
        val newGrid = !state.isGridView
        _uiState.update { it.copy(isGridView = newGrid) }
        if (state.categoryName == null) saveFolderDisplayPrefs(navigationPath(), state.sortMode, state.isSortAscending, newGrid)
    }

    fun toggleDualPane() {
        val state = _uiState.value
        if (state.categoryName == "Favoris") return
        val initializeSecond = !state.isDualPane && state.secondPath == null
        if (initializeSecond) {
            // Initialize from the already loaded folder; switching layouts never reads storage.
            navigationRoots[2] = navigationRoot(1)
            _uiState.update { it.copy(secondPath = state.currentPath, secondFiles = state.files) }
        }
        setActivePanel(1)
        _uiState.update { it.copy(isDualPane = !it.isDualPane) }
        // A category or an unfinished read cannot serve as a cached folder listing.
        if (initializeSecond && (state.isLoading || state.categoryName != null)) {
            loadFilesForPanel(state.currentPath, 2)
        }
    }

    fun setActivePanel(panel: Int) {
        if (panel !in 1..2 || (panel == 2 && !_uiState.value.isDualPane)) return
        _uiState.update { it.activate(panel) }
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

    fun deleteSelected() = removeItems(_uiState.value.selectedPaths.toList(), false)

    @Volatile private var deletionBusy = false
    private fun removeItems(paths: List<String>, trash: Boolean) {
        if (transferBusy() || paths.isEmpty()) return
        val panel = _uiState.value.activePanel
        val folderPath = navigationPath(panel)
        val visible = if (panel == 1) _uiState.value.files else _uiState.value.secondFiles
        if (_uiState.value.categoryName == null && paths.any { path -> visible.none { it.path == path } }) {
            _uiState.update { it.copy(error = "Élément sélectionné introuvable dans le dossier affiché. Actualisez avant de supprimer.") }
            return
        }
        deletionBusy = true
        _uiState.update { it.copy(deletionBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = fr.bonobo.filemanager.util.DeletionBatch.run(paths, trash) { path ->
                    if (trash) repository.moveToTrash(path) else deleteFileUseCase(path)
                }
                _uiState.update {
                    val completed = outcome.completed.toSet()
                    it.copy(selectedPaths = if (it.activePanel == panel) it.selectedPaths - completed else it.selectedPaths,
                        inactiveSelectedPaths = if (it.activePanel != panel) it.inactiveSelectedPaths - completed else it.inactiveSelectedPaths,
                        operationNotice = outcome.message, error = null)
                }
                if (navigationPath(panel) == folderPath) loadFilesForPanel(folderPath, panel)
            } finally {
                deletionBusy = false
                _uiState.update { it.copy(deletionBusy = false) }
                refreshArtifacts()
            }
        }
    }

    fun multiRename(
        prefix: String = "",
        suffix: String = "",
        find: String = "",
        replace: String = "",
        startCounter: Int? = null
    ) {
        if (transferBusy()) return
        val paths = _uiState.value.selectedPaths.toList().sorted()
        val items = _uiState.value.files + _uiState.value.secondFiles
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            
            val errors = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                val file = File(items.firstOrNull { it.path == path }?.name ?: File(path).name)
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
                
                renameFileUseCase(path, newName).exceptionOrNull()?.let { errors += it.message ?: "Renommage impossible" }
            }
            
            clearSelection()
            if (errors.isEmpty()) loadFiles() else _uiState.update { it.copy(isLoading = false, error = errors.joinToString("\n")) }
        }
    }

    fun moveToTrashSelected() = removeItems(_uiState.value.selectedPaths.toList(), true)

    fun loadCategory(type: String, query: String = "") {
        setActivePanel(1)
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
                    "Documents" -> fr.bonobo.filemanager.util.FileUtils.listDocumentFiles(Environment.getExternalStorageDirectory())
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

    fun reopenStorageRoot() {
        val panel = _uiState.value.activePanel
        val root = navigationRoot(panel)
        safParents.keys.removeAll { it.first == panel }
        loadFilesForPanel(root, panel)
    }

    fun refreshAfterResume() {
        refresh()
        if (_uiState.value.isDualPane) {
            val other = if (_uiState.value.activePanel == 1) 2 else 1
            loadFilesForPanel(navigationPath(other), other)
        }
    }

    fun refresh() {
        val category = _uiState.value.categoryName
        if (category != null && _uiState.value.activePanel == 1) {
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
        val panel = _uiState.value.activePanel
        if (panel == 1 && _uiState.value.categoryName == "Favoris") {
            loadCategory("Favoris", query)
            return
        }
        val counter = if (panel == 1) primaryLoadToken else secondaryLoadToken
        val searchToken = counter.incrementAndGet()
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            loadFiles()
            return
        }
        _uiState.update { if (panel == 1) it.copy(isLoading = true, error = null)
            else it.copy(isSecondLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { searchFilesUseCase(query) }.onSuccess { files ->
                _uiState.update {
                    if (searchToken != counter.get()) it
                    else if (panel == 1) it.copy(files = files, isLoading = false)
                    else it.copy(secondFiles = files, isSecondLoading = false)
                }
            }.onFailure { exception ->
                _uiState.update {
                    if (searchToken != counter.get()) it
                    else if (panel == 1) it.copy(isLoading = false, error = exception.message ?: "Erreur lors de la recherche")
                    else it.copy(isSecondLoading = false, error = exception.message ?: "Erreur lors de la recherche")
                }
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
        if (transferBusy()) return
        val cleanName = newName.trim()

        if (cleanName.isBlank()) {
            _uiState.update { it.copy(
                error = "Le nom ne peut pas être vide"
            ) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                renameFileUseCase(item.path, cleanName).getOrThrow()
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

    fun delete(item: FileItem) = removeItems(listOf(item.path), false)

    fun compress(item: FileItem, zipName: String, password: String? = null) {
        if (transferBusy()) return
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
        val destination = navigationPath()
        
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
        val destinationDirectory = File(navigationPath())

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
        if (transferBusy()) return
        // Cette méthode peut être conservée pour une extraction rapide au même endroit
        // Mais nous privilégions maintenant setPendingExtraction
        setPendingExtraction(item)
    }

    fun setClipboard(item: FileItem, isMove: Boolean) {
        clipboardParent = navigationPath()
        // Une fois l’opération Copier/Déplacer choisie, on quitte le mode
        // sélection. Sinon les appuis sur les dossiers continuent à les
        // sélectionner au lieu de permettre de naviguer dans leurs
        // sous-dossiers pour choisir la destination.
        _uiState.update { it.copy(
            clipboard = ClipboardState(
                file = item,
                isMove = isMove
            ),
            selectedPaths = emptySet()
        ) }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    private fun transferBusy(): Boolean {
        if (deletionBusy) {
            _uiState.update { it.copy(error = "Une suppression est en cours ; attendez son résultat.") }
            return true
        }
        if (_uiState.value.restoreBusy) {
            _uiState.update { it.copy(error = "Une restauration est en cours ; attendez sa fin.") }
            return true
        }
        if (!transferManager.state.value.active) return false
        _uiState.update { it.copy(error = "Attendez l’arrêt du transfert avant de modifier ou supprimer des fichiers.") }
        return true
    }
    val transfers = transferManager.state
    fun cancelTransfer() = transferManager.cancel()
    fun dismissTransfer() = transferManager.dismiss()

    fun copySelected(isMove: Boolean) {
        val state = _uiState.value
        val files = (state.files + state.secondFiles).distinctBy { it.path }.filter { it.path in state.selectedPaths }
        if (files.isEmpty()) return
        _uiState.update { it.copy(clipboard = ClipboardState(files.first(), isMove, files), selectedPaths = emptySet()) }
    }

    fun pasteClipboard() {
        if (transferBusy()) return
        val clipboard = _uiState.value.clipboard ?: return
        if (_uiState.value.activePanel == 1 && _uiState.value.categoryName != null) {
            _uiState.update { it.copy(error = "Ouvrez un dossier pour coller les éléments.") }; return
        }
        val factory = fr.bonobo.filemanager.data.transfer.TransferEngineFactory
        val request = fr.bonobo.filemanager.domain.transfer.TransferRequest(clipboard.files.map(factory::source),
            factory.storage(navigationPath()), if (clipboard.isMove) fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE
            else fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY)
        val job = try { transferManager.start(request, transferEngines.local()) }
        catch (e: Exception) { _uiState.update { it.copy(error = e.message) }; return }
        if (job == null) { _uiState.update { it.copy(error = "Un transfert est déjà en cours. Attendez sa fin ou demandez son annulation.") }; return }
        _uiState.update { it.copy(error = null) }
        val transferId = transfers.value.id
        viewModelScope.launch {
            job.join()
            val completed = transferManager.result(transferId)?.results.orEmpty().filter { it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.DONE }.map { it.source.ref.path }.toSet()
            val remaining = clipboard.files.filter { it.path !in completed }
            _uiState.update { it.copy(clipboard = if (it.clipboard != clipboard) it.clipboard else remaining.firstOrNull()?.let { first -> ClipboardState(first, clipboard.isMove, remaining) }) }
            if (_uiState.value.categoryName == null) {
                loadFilesForPanel(navigationPath(1), 1)
                if (_uiState.value.isDualPane) loadFilesForPanel(navigationPath(2), 2)
            }
        }
    }


    fun goUp() {
        val state = _uiState.value
        val path = navigationPath(state.activePanel)
        if (state.activePanel == 1 && favoriteRoot != null && path == favoriteRoot) {
            loadCategory("Favoris")
            return
        }
        if (state.activePanel == 1 && state.categoryName != null) return

        if (fr.bonobo.filemanager.util.SafFiles.isSaf(path)) {
            safParents[state.activePanel to path]?.let { loadFiles(it) }
            return
        }
        val current = File(path).toPath().toAbsolutePath().normalize()
        val root = File(navigationRoot(state.activePanel)).toPath().toAbsolutePath().normalize()
        val parent = current.parent

        if (parent != null && current != root && parent.startsWith(root)) {
            loadFiles(parent.toString())
        }
    }

    fun isAtRoot(): Boolean {
        val state = _uiState.value
        if (state.activePanel == 1 && state.categoryName != null) return true
        if (state.activePanel == 1 && favoriteRoot != null) return false
        if (fr.bonobo.filemanager.util.SafFiles.isSaf(navigationPath())) return navigationPath() == navigationRoot()
        val current = File(navigationPath(state.activePanel)).toPath().toAbsolutePath().normalize()
        val root = File(navigationRoot(state.activePanel)).toPath().toAbsolutePath().normalize()
        return current == root || current.parent == null
    }

    fun openLocalStorage() {
        navigationRoots[_uiState.value.activePanel] = rootPath
        openFolder(rootPath, resetNavigationRoot = true)
    }

    fun resetToLocalRoot() {
        favoriteRoot = null
        setActivePanel(1)
        navigationRoots[1] = rootPath
        _uiState.update { it.copy(
            currentPath = rootPath,
            categoryName = null,
            searchQuery = "",
            error = null
        ) }
        loadFiles(rootPath)
    }

    fun createFolder(name: String) {
        if (transferBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createFolder(navigationPath(), name)
                .onSuccess { loadFiles() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun createFile(name: String) {
        if (transferBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createFile(navigationPath(), name)
                .onSuccess { loadFiles() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun moveToTrash(item: FileItem) = removeItems(listOf(item.path), true)

    fun restoreFromTrash(item: FileItem) {
        if (transferBusy() || _uiState.value.restoreBusy) return
        _uiState.update { it.copy(pendingRestore = item, pendingRestores = listOf(item), error = null) }
    }

    fun restoreSelectedFromTrash() {
        if (transferBusy() || _uiState.value.restoreBusy) return
        val items = _uiState.value.files.filter { it.path in _uiState.value.selectedPaths }
        _uiState.update { it.copy(pendingRestores = items, pendingRestore = items.firstOrNull(), error = null) }
    }

    fun dismissNotice() { _uiState.update { it.copy(operationNotice = null) } }
    fun dismissRestore() { if (!_uiState.value.restoreBusy) _uiState.update { it.copy(pendingRestore = null, pendingRestores = emptyList()) } }
    fun restoreTrashTo(parent: String) = restoreBatch(parent)
    fun restoreBatch(parent: String? = null) {
        if (transferBusy() || _uiState.value.restoreBusy) return
        val items = _uiState.value.pendingRestores
        if (items.isEmpty()) return
        _uiState.update { it.copy(restoreBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcomes = mutableListOf<Pair<String, Throwable?>>()
                fr.bonobo.filemanager.util.BatchTransfer.run(items, { _, _ -> },
                    { item -> if (parent == null) repository.restoreFromTrash(item) else repository.restoreTrashTo(item, parent) },
                    { item, result -> outcomes += item.name to result.exceptionOrNull() })
                val errors = outcomes.filter { it.second != null }.joinToString("\n") { "${it.first} : ${it.second?.message}" }
                _uiState.update { it.copy(pendingRestore = null, pendingRestores = emptyList(), selectedPaths = emptySet()) }
                loadCategory("Corbeille")
                _uiState.update { it.copy(operationNotice = "${outcomes.count { entry -> entry.second == null }}/${items.size} restauration(s) vérifiée(s). Les sources restent dans la corbeille." + if (errors.isEmpty()) "" else "\n$errors") }
            } finally { _uiState.update { it.copy(restoreBusy = false) } }
        }
    }

    fun emptyTrash() {
        if (transferBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.emptyTrash()
                .onSuccess { loadCategory("Corbeille") }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun encryptFile(item: FileItem, password: String) {
        if (transferBusy()) return
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
        if (transferBusy()) return
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
            val currentPath = navigationPath()
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
