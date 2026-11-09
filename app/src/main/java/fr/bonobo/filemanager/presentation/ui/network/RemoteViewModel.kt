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
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RemoteSort { NAME, DATE, SIZE }

data class RemoteUiState(
    val connection: RemoteConnection? = null,
    val currentPath: String = "/",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
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
    val thumbnails: Map<String, String> = emptyMap()
)

enum class RemoteOperation { COPY, MOVE, DUPLICATE }

data class RemoteClipboard(val item: FileItem, val operation: RemoteOperation, val items: List<FileItem> = listOf(item))

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remoteRepository: fr.bonobo.filemanager.data.repository.RemoteFileRepository,
    private val transferManager: fr.bonobo.filemanager.data.transfer.TransferManager,
    private val transferEngines: fr.bonobo.filemanager.data.transfer.TransferEngineFactory,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    fun connect(connection: RemoteConnection) {
        val prefs = context.getSharedPreferences("remote_explorer", Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(favoriteKey(connection), emptySet())?.toSet() ?: emptySet()
        _uiState.update { it.copy(connection = connection, currentPath = "/", favoritePaths = favorites, selectedPaths = emptySet(), clipboard = null, files = emptyList(), message = null) }
        loadRemoteFiles("/")
    }

    private fun favoriteKey(connection: RemoteConnection) =
        "favorites_${connection.type}_${connection.host}_${connection.port}_${connection.share.orEmpty()}"

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

    fun toggleFavoriteCurrentPath() {
        val state = _uiState.value
        val connection = state.connection ?: return
        val updated = state.favoritePaths.toMutableSet().apply {
            if (!add(state.currentPath)) remove(state.currentPath)
        }.toSet()
        context.getSharedPreferences("remote_explorer", Context.MODE_PRIVATE).edit()
            .putStringSet(favoriteKey(connection), updated).apply()
        _uiState.update { it.copy(favoritePaths = updated, message = if (state.currentPath in updated) "Dossier réseau ajouté aux favoris" else "Favori réseau retiré") }
    }

    fun openFavorite(path: String) = loadRemoteFiles(path)

    fun toggleFavorite(item: FileItem) {
        if (!item.isDirectory) return
        val state = _uiState.value
        val connection = state.connection ?: return
        val updated = state.favoritePaths.toMutableSet().apply {
            if (!add(item.path)) remove(item.path)
        }.toSet()
        context.getSharedPreferences("remote_explorer", Context.MODE_PRIVATE).edit()
            .putStringSet(favoriteKey(connection), updated).apply()
        _uiState.update { it.copy(favoritePaths = updated, message = if (item.path in updated) "Dossier réseau ajouté aux favoris" else "Favori réseau retiré") }
    }

    fun requestThumbnail(item: FileItem) {
        if (item.isDirectory || item.path in _uiState.value.thumbnails) return
        val ext = item.name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif")) return
        val connection = _uiState.value.connection ?: return
        viewModelScope.launch {
            val key = (connection.host + "_" + connection.share.orEmpty() + "_" + item.path).hashCode().toUInt().toString(16)
            val cached = java.io.File(context.cacheDir, "remote_thumb_$key.$ext")
            if (!cached.exists()) {
                remoteRepository.downloadToCache(connection, item.path, cached).onFailure { cached.delete(); return@launch }
            }
            _uiState.update { it.copy(thumbnails = it.thumbnails + (item.path to cached.absolutePath)) }
        }
    }

    fun loadRemoteFiles(path: String) {
        val connection = _uiState.value.connection ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedPaths = if (it.currentPath != path) emptySet() else it.selectedPaths, currentPath = path, error = null) }
            
            remoteRepository.listRemoteFiles(connection, path)
                .onSuccess { remoteFiles ->
                    _uiState.update { state -> state.copy(
                        files = sorted(remoteFiles, state.sort, state.sortAscending),
                        isLoading = false
                    ) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Erreur de connexion : ${e.message}"
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
            _uiState.update { it.copy(isLoading = true, error = null, openUri = null, internalOpenPath = null, internalOpenKind = null) }
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
                    _uiState.update { it.copy(isLoading = false, error = "Impossible d'ouvrir le fichier : ${e.message}") }
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
            _uiState.update { it.copy(isLoading = true) }
            remoteRepository.deleteRemoteFile(connection, item.path)
                .onSuccess {
                    loadRemoteFiles(_uiState.value.currentPath)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Échec suppression : ${e.message}") }
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

    fun pasteClipboardToLocal(destinationDirectory: String) {
        startClipboardTransfer(fr.bonobo.filemanager.data.transfer.TransferEngineFactory.storage(destinationDirectory))
    }

    private fun startClipboardTransfer(destination: fr.bonobo.filemanager.domain.transfer.StorageRef) {
        val current = _uiState.value
        val connection = current.connection ?: return
        val clipboard = current.clipboard ?: return
        val sources = clipboard.items.map { fr.bonobo.filemanager.data.transfer.TransferEngineFactory.remoteSource(connection, it) }
        val operation = when (clipboard.operation) {
            RemoteOperation.COPY -> fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY
            RemoteOperation.MOVE -> fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE
            RemoteOperation.DUPLICATE -> fr.bonobo.filemanager.domain.transfer.TransferOperation.DUPLICATE
        }
        val job = try { transferManager.start(fr.bonobo.filemanager.domain.transfer.TransferRequest(sources, destination, operation),
            transferEngines.network(connection, clipboard.items)) }
        catch (e: Exception) { _uiState.update { it.copy(error = e.message) }; return }
        if (job == null) { _uiState.update { it.copy(error = "Un transfert est déjà en cours ; attendez son arrêt réel.") }; return }
        _uiState.update { it.copy(error = null, message = null) }
        val transferId = transfers.value.id
        viewModelScope.launch {
            job.join()
            val completed = transferManager.result(transferId)?.results.orEmpty().filter { it.status == fr.bonobo.filemanager.domain.transfer.ItemStatus.DONE }.map { it.source.ref.path }.toSet()
            val remaining = clipboard.items.filter { it.path !in completed }
            _uiState.update { it.copy(clipboard = if (it.clipboard != clipboard) it.clipboard else remaining.firstOrNull()?.let { first -> RemoteClipboard(first, clipboard.operation, remaining) }) }
            loadRemoteFiles(_uiState.value.currentPath)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun pasteHere() {
        val current = _uiState.value
        val connection = current.connection ?: return
        startClipboardTransfer(fr.bonobo.filemanager.domain.transfer.StorageRef.Remote(connection.id, connection.type.name, current.currentPath))
    }
}
