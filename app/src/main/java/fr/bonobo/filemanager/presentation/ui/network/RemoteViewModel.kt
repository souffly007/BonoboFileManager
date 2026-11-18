package fr.bonobo.filemanager.presentation.ui.network

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.bonobo.filemanager.data.repository.FtpClientRepository
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RemoteSort { NAME, DATE, SIZE }

data class RemoteUiState(
    val connection: RemoteConnection? = null,
    val currentPath: String = "/",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Raw code of the last error (NTSTATUS…), shown only under « Détails techniques ». */
    val errorDetail: String? = null,
    val openUri: Uri? = null,
    val openMimeType: String? = null,
    val internalOpenPath: String? = null,
    val internalOpenKind: String? = null,
    val downloadFile: FileItem? = null,
    val clipboard: RemoteClipboard? = null,
    val message: String? = null,
    val sort: RemoteSort = RemoteSort.NAME,
    val sortAscending: Boolean = true,
    val favoritePaths: Set<String> = emptySet(),
    val selectedPaths: Set<String> = emptySet(),
    val thumbnails: Map<String, String> = emptyMap(),
    /** Destination conflict waiting for Remplacer / Renommer / Ignorer / Fusionner. */
    val conflictPrompt: RemoteConflictPrompt? = null
) {
    fun isFavorite(path: String): Boolean =
        fr.bonobo.filemanager.data.local.RemoteBookmarkStore.normalize(path) in favoritePaths
}

/** Direction of a transfer that met a destination conflict. */
enum class ConflictTarget { TO_PHONE, TO_SERVER }

/** Same walk as the local ConflictPrompt: one dialog per conflicting element, or « Appliquer à tous ». */
data class RemoteConflictPrompt(
    val target: ConflictTarget,
    val items: List<FileItem>,
    val conflicts: List<FileItem>,
    val destination: String,
    val isMove: Boolean,
    /** Element already present in the destination, by lower-case name. */
    val existing: Map<String, FileItem> = emptyMap(),
    val index: Int = 0,
    val decisions: Map<String, fr.bonobo.filemanager.domain.transfer.ConflictAction> = emptyMap(),
    val compareHash: Boolean = false
) {
    val current: FileItem get() = conflicts[index]
    val currentExisting: FileItem? get() = existing[current.name.lowercase()]
    val remaining: Int get() = conflicts.size - index
}

/** Elements of [items] whose name already exists in [present] (case-insensitive, like SMB and FAT/exFAT). */
fun conflictsWith(items: List<FileItem>, present: Collection<FileItem>): Pair<List<FileItem>, Map<String, FileItem>> {
    val byName = present.associateBy { it.name.lowercase() }
    val conflicts = items.filter { it.name.lowercase() in byName }
    return conflicts to byName.filterKeys { name -> conflicts.any { it.name.lowercase() == name } }
}

enum class RemoteOperation { COPY, MOVE, DUPLICATE }

/** Image extensions a thumbnail is attempted for. */
private val THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
const val THUMBNAIL_MAX_BYTES = 10L * 1024 * 1024
private const val THUMBNAIL_PARALLEL = 2
private const val THUMBNAIL_QUEUE = 24

/** Image of known size up to [THUMBNAIL_MAX_BYTES]; anything else keeps its icon. */
fun wantsThumbnail(item: FileItem): Boolean = !item.isDirectory && item.size in 1..THUMBNAIL_MAX_BYTES &&
    item.name.substringAfterLast('.', "").lowercase() in THUMBNAIL_EXTENSIONS

data class RemoteClipboard(val item: FileItem, val operation: RemoteOperation, val items: List<FileItem> = listOf(item))

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remoteRepository: fr.bonobo.filemanager.data.repository.RemoteFileRepository,
    private val transferManager: fr.bonobo.filemanager.data.transfer.TransferManager,
    private val transferEngines: fr.bonobo.filemanager.data.transfer.TransferEngineFactory,
    remoteConnections: fr.bonobo.filemanager.domain.repository.IRemoteRepository,
    private val bookmarkStore: fr.bonobo.filemanager.data.local.RemoteBookmarkStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    /** SMB connections offered by « Envoyer vers… ». */
    val smbConnections: StateFlow<List<RemoteConnection>> = remoteConnections.getAllConnections()
        .map { all -> all.filter { it.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every saved connection, used to reopen a network favorite. */
    private val allConnections: StateFlow<List<RemoteConnection>> = remoteConnections.getAllConnections()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Network folders in favorites (shown in « Favoris »), with the current name of their
     * connection; favorites of a connection that no longer exists are left out.
     */
    val networkBookmarks: StateFlow<List<fr.bonobo.filemanager.data.local.RemoteBookmark>> =
        kotlinx.coroutines.flow.combine(bookmarkStore.bookmarks, allConnections) { bookmarks, connections ->
            val byId = connections.associateBy { it.id }
            bookmarks.mapNotNull { b -> byId[b.connectionId]?.let { b.copy(connectionName = it.name) } }
                .sortedWith(compareBy({ it.connectionName.lowercase() }, { it.path.lowercase() }))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Keep the explorer's favorite icons in step with the shared store (removal from « Favoris »…).
        viewModelScope.launch {
            bookmarkStore.bookmarks.collect {
                val id = _uiState.value.connection?.id ?: return@collect
                _uiState.update { s -> s.copy(favoritePaths = bookmarkStore.pathsFor(id)) }
            }
        }
    }

    /** Opens a network favorite in the remote explorer; false if its connection no longer exists. */
    fun openBookmark(bookmark: fr.bonobo.filemanager.data.local.RemoteBookmark): Boolean {
        val connection = allConnections.value.firstOrNull { it.id == bookmark.connectionId } ?: return false
        if (_uiState.value.connection == connection) loadRemoteFiles(bookmark.path)
        else connect(connection, startPath = bookmark.path)
        return true
    }

    fun removeBookmark(bookmark: fr.bonobo.filemanager.data.local.RemoteBookmark) = bookmarkStore.remove(bookmark)

    /** « Envoyer vers… »: opens [connection]; the phone clipboard is then pasted with « Mettre ici ». */
    fun connectForUpload(connection: RemoteConnection) {
        connect(connection)
        _uiState.update { it.copy(message = "Choisissez le dossier du serveur puis appuyez sur « Mettre ici »") }
    }

    fun connect(connection: RemoteConnection, startPath: String = "/") {
        migrateLegacyFavorites(connection)
        val favorites = bookmarkStore.pathsFor(connection.id)
        _uiState.value.connection?.takeIf { it != connection }?.let(remoteRepository::release)
        cancelThumbnails()
        thumbnailRefused.clear()
        _uiState.update { it.copy(connection = connection, currentPath = "/", favoritePaths = favorites, selectedPaths = emptySet(),
            clipboard = null, files = emptyList(), message = null, thumbnails = emptyMap()) }
        loadRemoteFiles(startPath)
        // Partial files left on this server by an earlier failure or a killed app (silent, best effort).
        viewModelScope.launch { runCatching { remoteRepository.cleanPending(connection) } }
    }

    /** Leaving the explorer: thumbnails stop, the SMB session closes (or when the running transfer ends). */
    fun leaveExplorer() {
        cancelThumbnails()
        _uiState.value.connection?.let(remoteRepository::release)
    }

    override fun onCleared() {
        _uiState.value.connection?.let(remoteRepository::release)
        super.onCleared()
    }

    private fun favoriteKey(connection: RemoteConnection) =
        "favorites_${connection.type}_${connection.host}_${connection.port}_${connection.share.orEmpty()}"

    /** Before 1.3.0-beta21 network favorites lived only here, invisible from « Favoris »: moved once. */
    private fun migrateLegacyFavorites(connection: RemoteConnection) {
        val prefs = context.getSharedPreferences("remote_explorer", Context.MODE_PRIVATE)
        val key = favoriteKey(connection)
        val legacy = prefs.getStringSet(key, null) ?: return
        bookmarkStore.addAll(connection.id, connection.name, legacy)
        prefs.edit().remove(key).apply()
    }

    private fun toggleFavoritePath(path: String) {
        val connection = _uiState.value.connection ?: return
        bookmarkStore.toggle(connection.id, connection.name, path,
            fr.bonobo.filemanager.data.local.RemoteBookmarkStore.displayNameOf(path, connection.name))
        val added = bookmarkStore.isBookmarked(connection.id, path)
        _uiState.update { it.copy(favoritePaths = bookmarkStore.pathsFor(connection.id),
            message = if (added) "Dossier réseau ajouté aux favoris" else "Favori réseau retiré") }
    }

    private fun sorted(files: List<FileItem>, sort: RemoteSort, ascending: Boolean): List<FileItem> {
        val comparator = when (sort) {
            RemoteSort.NAME -> compareBy<FileItem> { it.name.lowercase() }
            RemoteSort.DATE -> compareBy<FileItem> { it.lastModified.time }
            RemoteSort.SIZE -> compareBy<FileItem> { it.size }
        }
        val ordered = if (ascending) comparator else comparator.reversed()
        // Folders are always kept apart and before files, as in the local explorer.
        return files.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(ordered))
    }

    fun setSort(sort: RemoteSort) {
        _uiState.update { state ->
            val asc = if (state.sort == sort) !state.sortAscending else true
            state.copy(sort = sort, sortAscending = asc, files = sorted(state.files, sort, asc))
        }
    }

    fun toggleFavoriteCurrentPath() = toggleFavoritePath(_uiState.value.currentPath)

    fun openFavorite(path: String) = loadRemoteFiles(path)

    fun toggleFavorite(item: FileItem) {
        if (item.isDirectory) toggleFavoritePath(item.path)
    }

    // Thumbnails: only images up to THUMBNAIL_MAX_BYTES, read with that limit, THUMBNAIL_PARALLEL at a
    // time, at most THUMBNAIL_QUEUE waiting, all cancelled when the folder changes. Others keep their icon.
    private val thumbnailPermits = kotlinx.coroutines.sync.Semaphore(THUMBNAIL_PARALLEL)
    private val thumbnailJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val thumbnailRefused = mutableSetOf<String>()

    fun requestThumbnail(item: FileItem) {
        val state = _uiState.value
        if (!wantsThumbnail(item) || item.path in state.thumbnails || item.path in thumbnailRefused || item.path in thumbnailJobs) return
        if (thumbnailJobs.size >= THUMBNAIL_QUEUE) return
        val connection = state.connection ?: return
        val folder = state.currentPath
        val ext = item.name.substringAfterLast('.', "").lowercase()
        // LAZY: registered in the queue before it can run (and finish) on the main thread.
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                thumbnailPermits.acquire()
                try {
                    if (_uiState.value.currentPath != folder) return@launch
                    val key = (connection.host + "_" + connection.share.orEmpty() + "_" + item.path + "_" + item.size)
                        .hashCode().toUInt().toString(16)
                    val cached = java.io.File(context.cacheDir, "remote_thumb_$key.$ext")
                    if (!cached.exists()) {
                        // Written aside then renamed: a cancelled read never leaves a truncated thumbnail.
                        val partial = java.io.File(context.cacheDir, "remote_thumb_$key.$ext.part")
                        val result = remoteRepository.downloadToCache(connection, item.path, partial, thumbnail = true, maxBytes = THUMBNAIL_MAX_BYTES)
                        if (result.isFailure || !partial.renameTo(cached)) {
                            partial.delete()
                            thumbnailRefused += item.path
                            return@launch
                        }
                    }
                    _uiState.update { it.copy(thumbnails = it.thumbnails + (item.path to cached.absolutePath)) }
                } finally { thumbnailPermits.release() }
            } finally { if (thumbnailJobs[item.path] === coroutineContext[kotlinx.coroutines.Job]) thumbnailJobs.remove(item.path) }
        }
        thumbnailJobs[item.path] = job
        job.start()
    }

    private fun cancelThumbnails() {
        thumbnailJobs.values.toList().forEach { it.cancel() }
        thumbnailJobs.clear()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * The current path (used by « Mettre ici », the favorites and « Retour ») only changes once the
     * listing succeeded: a folder that fails to open never becomes the paste destination.
     */
    fun loadRemoteFiles(path: String) {
        val connection = _uiState.value.connection ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, errorDetail = null) }
            remoteRepository.listRemoteFiles(connection, path)
                .onSuccess { remoteFiles ->
                    if (_uiState.value.currentPath != path) cancelThumbnails()
                    _uiState.update { state -> state.copy(
                        files = sorted(remoteFiles, state.sort, state.sortAscending),
                        selectedPaths = if (state.currentPath != path) emptySet() else state.selectedPaths,
                        currentPath = path,
                        isLoading = false
                    ) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = fr.bonobo.filemanager.data.repository.SmbErrors.userMessage(e),
                        errorDetail = fr.bonobo.filemanager.data.repository.SmbErrors.technicalDetail(e)
                    ) }
                }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current == "/") return
        
        val parent = current.substringBeforeLast("/", "").ifEmpty { "/" }
        loadRemoteFiles(parent)
    }

    fun open(item: FileItem) {
        if (item.isDirectory) {
            loadRemoteFiles(item.path)
            return
        }
        val connection = _uiState.value.connection ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, errorDetail = null, openUri = null, internalOpenPath = null, internalOpenKind = null) }
            // SPACE_CHECK on the private cache before the first byte.
            val space = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    fr.bonobo.filemanager.util.SpaceCheck.require(context, context.cacheDir.path, item.size.coerceAtLeast(0), 1,
                        storage = if (connection.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB)
                            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SMB else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.OTHER)
                }
            }
            space.exceptionOrNull()?.let { e ->
                _uiState.update { it.copy(isLoading = false, error = "Impossible d'ouvrir le fichier : ${e.message}", errorDetail = null) }
                return@launch
            }
            val safeName = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destination = java.io.File.createTempFile("bonobo_smb_", "_$safeName", context.cacheDir)
            remoteRepository.downloadToCache(connection, item.path, destination)
                .onSuccess { file ->
                    val ext = item.name.substringAfterLast('.', "").lowercase()
                    val kind = when (ext) {
                        "txt", "md" -> "text"
                        "pdf" -> "pdf"
                        "docx", "odt", "xlsx", "ods", "pptx", "odp" -> "office"
                        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp" -> "video"
                        "mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus" -> "audio"
                        else -> null
                    }
                    if (kind != null) {
                        if (kind == "text") activeRemoteEditPath = item.path
                        _uiState.update { it.copy(isLoading = false, internalOpenPath = file.absolutePath, internalOpenKind = kind) }
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        _uiState.update { it.copy(isLoading = false, openUri = uri, openMimeType = item.mimeType) }
                    }
                }
                .onFailure { e ->
                    destination.delete()
                    _uiState.update { it.copy(isLoading = false,
                        error = "Impossible d'ouvrir le fichier : ${fr.bonobo.filemanager.data.repository.SmbErrors.userMessage(e)}",
                        errorDetail = fr.bonobo.filemanager.data.repository.SmbErrors.technicalDetail(e)) }
                }
        }
    }

    private var activeRemoteEditPath: String? = null

    suspend fun saveRemoteText(localPath: String, text: String): Result<Unit> {
        val connection = _uiState.value.connection ?: return Result.failure(Exception("Connexion SMB absente"))
        val remotePath = activeRemoteEditPath ?: return Result.failure(Exception("Chemin SMB absent"))
        val file = java.io.File(localPath)
        return runCatching { file.writeText(text) }.fold(
            onSuccess = { remoteRepository.uploadFromCache(connection, remotePath, file) },
            onFailure = { Result.failure(it) }
        )
    }

    fun consumeInternalOpen() {
        _uiState.update { it.copy(internalOpenPath = null, internalOpenKind = null) }
    }

    fun requestDownload(item: FileItem) {
        if (!item.isDirectory) _uiState.update { it.copy(downloadFile = item, error = null) }
    }
    fun dismissDownload() { _uiState.update { it.copy(downloadFile = null) } }
    fun download(destination: Uri? = null) {
        if (transfers.value.active) { _uiState.update { it.copy(error = "Attendez la fin du transfert en cours.") }; return }
        val current = _uiState.value
        val connection = current.connection
        val item = current.downloadFile
        if (connection == null || item == null) {
            if (destination != null) fr.bonobo.filemanager.service.RemoteDownloadTarget.deleteDocument(context, destination)
            return
        }
        try {
            fr.bonobo.filemanager.service.RemoteDownloadService.start(context, connection, item, destination)
            dismissDownload()
        } catch (e: Exception) {
            if (destination != null) fr.bonobo.filemanager.service.RemoteDownloadTarget.deleteDocument(context, destination)
            _uiState.update { it.copy(downloadFile = null, error = "Téléchargement impossible : ${e.message}") }
        }
    }

    fun consumeOpenUri() {
        _uiState.update { it.copy(openUri = null, openMimeType = null) }
    }

    fun refresh() {
        loadRemoteFiles(_uiState.value.currentPath)
    }

    fun deleteFile(item: FileItem) {
        if (transfers.value.active) { _uiState.update { it.copy(error = "Attendez l’arrêt du transfert avant de supprimer une source.") }; return }
        val connection = _uiState.value.connection ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, errorDetail = null) }
            remoteRepository.deleteRemoteFile(connection, item.path)
                .onSuccess {
                    loadRemoteFiles(_uiState.value.currentPath)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false,
                        error = "Échec suppression : ${fr.bonobo.filemanager.data.repository.SmbErrors.userMessage(e)}",
                        errorDetail = fr.bonobo.filemanager.data.repository.SmbErrors.technicalDetail(e)) }
                }
        }
    }

    fun toggleSelection(path: String) {
        _uiState.update { state -> state.copy(selectedPaths = state.selectedPaths.toMutableSet().apply { if (!add(path)) remove(path) }) }
    }
    fun clearSelection() { _uiState.update { it.copy(selectedPaths = emptySet()) } }
    fun prepareSelected(move: Boolean) {
        val state = _uiState.value
        val items = state.files.filter { it.path in state.selectedPaths }
        if (items.isEmpty()) return
        if (state.connection?.type != fr.bonobo.filemanager.domain.model.ConnectionType.SMB && items.any { it.isDirectory }) {
            _uiState.update { it.copy(error = "Ce protocole ne prend pas en charge le transfert de dossiers sélectionnés.") }; return
        }
        _uiState.update { it.copy(selectedPaths = emptySet(), clipboard = RemoteClipboard(items.first(),
            if (move) RemoteOperation.MOVE else RemoteOperation.COPY, items), message = "${items.size} éléments prêts à coller", error = null) }
    }

    fun prepareCopy(item: FileItem) {
        _uiState.update { it.copy(clipboard = RemoteClipboard(item, RemoteOperation.COPY), message = "« ${item.name} » prêt à copier", error = null) }
    }

    fun prepareMove(item: FileItem) {
        _uiState.update { it.copy(clipboard = RemoteClipboard(item, RemoteOperation.MOVE), message = "Choisissez le dossier puis appuyez sur Mettre ici", error = null) }
    }

    fun prepareDuplicate(item: FileItem) {
        _uiState.update { it.copy(clipboard = RemoteClipboard(item, RemoteOperation.DUPLICATE), message = "« ${item.name} » prêt à être dupliqué", error = null) }
    }

    fun cancelClipboard() {
        _uiState.update { it.copy(clipboard = null, message = null) }
    }

    val transfers = transferManager.state
    fun cancelTransfer() = transferManager.cancel()
    fun dismissTransfer() = transferManager.dismiss()

    /** Server → phone (internal storage or USB key): conflicts are asked first, as for a local paste. */
    fun pasteClipboardToLocal(destinationDirectory: String) {
        val current = _uiState.value
        val clipboard = current.clipboard ?: return
        if (current.connection == null) return
        if (transfers.value.active) { _uiState.update { it.copy(error = "Un transfert est déjà en cours ; attendez son arrêt réel.") }; return }
        viewModelScope.launch {
            val present = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    if (fr.bonobo.filemanager.util.SafFiles.isSaf(destinationDirectory)) fr.bonobo.filemanager.util.SafFiles(context).list(destinationDirectory)
                    else (java.io.File(destinationDirectory).listFiles() ?: emptyArray()).map { with(fr.bonobo.filemanager.util.FileUtils) { it.toFileItem() } }
                }.getOrNull()
            }
            val (conflicts, existing) = if (present == null) emptyList<FileItem>() to emptyMap() else conflictsWith(clipboard.items, present)
            if (conflicts.isEmpty()) startClipboardTransfer(fr.bonobo.filemanager.data.transfer.TransferEngineFactory.storage(destinationDirectory))
            else _uiState.update { it.copy(conflictPrompt = RemoteConflictPrompt(ConflictTarget.TO_PHONE, clipboard.items, conflicts,
                destinationDirectory, clipboard.operation == RemoteOperation.MOVE, existing)) }
        }
    }

    // ─────────────────────────────── Phone → server ───────────────────────────────

    /** Called with the phone paths done (or ignored) once an upload ends, so the phone clipboard is pruned. */
    private var uploadDone: ((Set<String>) -> Unit)? = null

    /** « Mettre ici » with the phone clipboard (internal storage or USB key) into the displayed SMB folder. */
    fun uploadHere(files: List<FileItem>, isMove: Boolean, onDone: (Set<String>) -> Unit) {
        val current = _uiState.value
        val connection = current.connection ?: return
        if (connection.type != fr.bonobo.filemanager.domain.model.ConnectionType.SMB || files.isEmpty()) return
        if (transfers.value.active) { _uiState.update { it.copy(error = "Un transfert est déjà en cours ; attendez son arrêt réel.") }; return }
        uploadDone = onDone
        val (conflicts, existing) = conflictsWith(files, current.files)
        if (conflicts.isEmpty()) startUpload(files, isMove, emptyMap(), false)
        else _uiState.update { it.copy(conflictPrompt = RemoteConflictPrompt(ConflictTarget.TO_SERVER, files, conflicts,
            current.currentPath, isMove, existing)) }
    }

    private fun startUpload(files: List<FileItem>, isMove: Boolean,
        decisions: Map<String, fr.bonobo.filemanager.domain.transfer.ConflictAction>, compareHash: Boolean) {
        val current = _uiState.value
        val connection = current.connection ?: return
        val skipped = files.filter { decisions[it.path] == fr.bonobo.filemanager.domain.transfer.ConflictAction.SKIP }
        val sending = files - skipped.toSet()
        if (sending.isEmpty()) {
            uploadDone?.invoke(skipped.map { it.path }.toSet())
            _uiState.update { it.copy(message = "Aucun élément envoyé : " +
                fr.bonobo.filemanager.domain.transfer.TransferText.plural(skipped.size, "élément ignoré", "éléments ignorés") + ".") }
            return
        }
        val factory = fr.bonobo.filemanager.data.transfer.TransferEngineFactory
        val request = fr.bonobo.filemanager.domain.transfer.TransferRequest(sending.map { item ->
            factory.source(item).copy(conflict = decisions[item.path] ?: fr.bonobo.filemanager.domain.transfer.ConflictAction.ASK,
                compareHash = compareHash)
        }, fr.bonobo.filemanager.domain.transfer.StorageRef.Remote(connection.id, connection.type.name, current.currentPath),
            if (isMove) fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE else fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY)
        val job = try { transferManager.start(request, transferEngines.upload(connection)) }
            catch (e: Exception) { _uiState.update { it.copy(error = e.message) }; return }
        if (job == null) { _uiState.update { it.copy(error = "Un transfert est déjà en cours ; attendez son arrêt réel.") }; return }
        _uiState.update { it.copy(error = null, errorDetail = null, message = null) }
        val transferId = transfers.value.id
        val onDone = uploadDone
        viewModelScope.launch {
            job.join()
            val done = transferManager.result(transferId)?.results.orEmpty().filter {
                it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.DONE || it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.SKIPPED
            }.map { it.source.ref.path }.toSet()
            onDone?.invoke(done + skipped.map { it.path })
            loadRemoteFiles(_uiState.value.currentPath)
        }
    }

    // ─────────────────────────────── Conflicts (both directions) ───────────────────────────────

    fun resolveConflict(action: fr.bonobo.filemanager.domain.transfer.ConflictAction, applyToAll: Boolean, compareHash: Boolean) {
        val prompt = _uiState.value.conflictPrompt ?: return
        val targets = if (applyToAll) prompt.conflicts.drop(prompt.index) else listOf(prompt.current)
        val decisions = prompt.decisions + targets.associate { it.path to action }
        val next = prompt.index + targets.size
        val hash = prompt.compareHash || compareHash
        if (next < prompt.conflicts.size) {
            _uiState.update { it.copy(conflictPrompt = prompt.copy(index = next, decisions = decisions, compareHash = hash)) }
            return
        }
        _uiState.update { it.copy(conflictPrompt = null) }
        when (prompt.target) {
            ConflictTarget.TO_SERVER -> startUpload(prompt.items, prompt.isMove, decisions, hash)
            ConflictTarget.TO_PHONE -> startClipboardTransfer(
                fr.bonobo.filemanager.data.transfer.TransferEngineFactory.storage(prompt.destination), null, decisions, hash)
        }
    }

    fun cancelConflicts() { _uiState.update { it.copy(conflictPrompt = null) } }

    // ─────────────────────────────── New folder, rename ───────────────────────────────

    fun createFolder(name: String) {
        val current = _uiState.value
        val connection = current.connection ?: return
        val trimmed = name.trim()
        fr.bonobo.filemanager.data.repository.SmbPaths.nameError(trimmed)?.let { reason ->
            _uiState.update { it.copy(error = "Création impossible : $reason", errorDetail = null) }; return
        }
        viewModelScope.launch {
            remoteRepository.createFolder(connection, current.currentPath, trimmed)
                .onSuccess {
                    _uiState.update { it.copy(message = "Dossier « $trimmed » créé", error = null, errorDetail = null) }
                    loadRemoteFiles(_uiState.value.currentPath)
                }
                .onFailure { e -> _uiState.update { it.copy(
                    error = "Création impossible : ${fr.bonobo.filemanager.data.repository.SmbErrors.userMessage(e)}",
                    errorDetail = fr.bonobo.filemanager.data.repository.SmbErrors.technicalDetail(e)) } }
        }
    }

    fun rename(item: FileItem, newName: String) {
        val connection = _uiState.value.connection ?: return
        if (transfers.value.active) { _uiState.update { it.copy(error = "Attendez la fin du transfert avant de renommer.") }; return }
        val trimmed = newName.trim()
        fr.bonobo.filemanager.data.repository.SmbPaths.nameError(trimmed)?.let { reason ->
            _uiState.update { it.copy(error = "Renommage impossible : $reason", errorDetail = null) }; return
        }
        if (trimmed == item.name) return
        viewModelScope.launch {
            remoteRepository.rename(connection, item.path, trimmed)
                .onSuccess { newPath ->
                    // A renamed folder keeps its place in the network favorites.
                    bookmarkStore.rename(connection.id, item.path, newPath)
                    _uiState.update { it.copy(message = "Renommé en « $trimmed »", error = null, errorDetail = null) }
                    loadRemoteFiles(_uiState.value.currentPath)
                }
                .onFailure { e -> _uiState.update { it.copy(
                    error = "Renommage impossible : ${fr.bonobo.filemanager.data.repository.SmbErrors.userMessage(e)}",
                    errorDetail = fr.bonobo.filemanager.data.repository.SmbErrors.technicalDetail(e)) } }
        }
    }

    /** « Mettre ici » on SMB: one PASTE_HERE line at the request and one at the end (each element logs its own COPY/MOVE/DUPLICATE). */
    private class PasteTrace(val operation: java.util.UUID?, val started: Long = System.nanoTime())

    private fun endPasteTrace(trace: PasteTrace?, result: fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result) {
        trace ?: return
        fr.bonobo.filemanager.diagnostic.Diagnostic.smb(fr.bonobo.filemanager.diagnostic.TechnicalJournal.SmbOp.PASTE_HERE,
            result, trace.operation, durationMs = (System.nanoTime() - trace.started) / 1_000_000)
    }

    private fun startClipboardTransfer(destination: fr.bonobo.filemanager.domain.transfer.StorageRef, trace: PasteTrace? = null,
        decisions: Map<String, fr.bonobo.filemanager.domain.transfer.ConflictAction> = emptyMap(), compareHash: Boolean = false) {
        val current = _uiState.value
        val connection = current.connection ?: return endPasteTrace(trace, fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED)
        val fullClipboard = current.clipboard ?: return endPasteTrace(trace, fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED)
        // « Ignorer » chosen in the conflict dialog: those elements are not sent to the motor at all.
        val skipped = fullClipboard.items.filter { decisions[it.path] == fr.bonobo.filemanager.domain.transfer.ConflictAction.SKIP }
        val kept = fullClipboard.items - skipped.toSet()
        if (kept.isEmpty()) {
            endPasteTrace(trace, fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.SKIPPED)
            _uiState.update { it.copy(clipboard = null, message = "Aucun élément copié : " +
                fr.bonobo.filemanager.domain.transfer.TransferText.plural(skipped.size, "élément ignoré", "éléments ignorés") + ".") }
            return
        }
        val clipboard = RemoteClipboard(kept.first(), fullClipboard.operation, kept)
        val sources = clipboard.items.map {
            fr.bonobo.filemanager.data.transfer.TransferEngineFactory.remoteSource(connection, it).copy(
                conflict = decisions[it.path] ?: fr.bonobo.filemanager.domain.transfer.ConflictAction.ASK, compareHash = compareHash)
        }
        val operation = when (clipboard.operation) {
            RemoteOperation.COPY -> fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY
            RemoteOperation.MOVE -> fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE
            RemoteOperation.DUPLICATE -> fr.bonobo.filemanager.domain.transfer.TransferOperation.DUPLICATE
        }
        val job = try { transferManager.start(fr.bonobo.filemanager.domain.transfer.TransferRequest(sources, destination, operation),
            transferEngines.network(connection, clipboard.items)) }
        catch (e: Exception) {
            endPasteTrace(trace, fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.ERROR)
            _uiState.update { it.copy(error = e.message) }; return
        }
        if (job == null) {
            endPasteTrace(trace, fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED)
            _uiState.update { it.copy(error = "Un transfert est déjà en cours ; attendez son arrêt réel.") }; return
        }
        _uiState.update { it.copy(error = null, errorDetail = null, message = null) }
        val transferId = transfers.value.id
        viewModelScope.launch {
            job.join()
            val results = transferManager.result(transferId)?.results.orEmpty()
            endPasteTrace(trace, when {
                results.isNotEmpty() && results.all { it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.DONE } -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK
                results.any { it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.CANCELLED } -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.CANCELLED
                else -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.ERROR
            })
            val completed = results.filter {
                it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.DONE || it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.SKIPPED
            }.map { it.source.ref.path }.toSet()
            val remaining = clipboard.items.filter { it.path !in completed }
            _uiState.update { it.copy(clipboard = if (it.clipboard != fullClipboard) it.clipboard else remaining.firstOrNull()?.let { first -> RemoteClipboard(first, clipboard.operation, remaining) }) }
            loadRemoteFiles(_uiState.value.currentPath)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun pasteHere() {
        val current = _uiState.value
        val connection = current.connection ?: return
        val trace = if (connection.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB) {
            PasteTrace(fr.bonobo.filemanager.diagnostic.Diagnostic.operation()).also {
                fr.bonobo.filemanager.diagnostic.Diagnostic.smb(fr.bonobo.filemanager.diagnostic.TechnicalJournal.SmbOp.PASTE_HERE,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.START, it.operation)
            }
        } else null
        startClipboardTransfer(fr.bonobo.filemanager.domain.transfer.StorageRef.Remote(connection.id, connection.type.name, current.currentPath), trace)
    }
}
