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

data class RemoteUiState(
    val connection: RemoteConnection? = null,
    val currentPath: String = "/",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val openUri: Uri? = null,
    val openMimeType: String? = null,
    val downloadFile: FileItem? = null
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remoteRepository: fr.bonobo.filemanager.data.repository.RemoteFileRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    fun connect(connection: RemoteConnection) {
        _uiState.update { it.copy(connection = connection, currentPath = "/") }
        loadRemoteFiles("/")
    }

    fun loadRemoteFiles(path: String) {
        val connection = _uiState.value.connection ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentPath = path, error = null) }
            
            remoteRepository.listRemoteFiles(connection, path)
                .onSuccess { remoteFiles ->
                    _uiState.update { it.copy(
                        files = remoteFiles,
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
            _uiState.update { it.copy(isLoading = true, error = null, openUri = null) }
            val safeName = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destination = java.io.File.createTempFile("bonobo_", "_$safeName", context.cacheDir)
                .also { it.deleteOnExit() }
            remoteRepository.downloadToCache(connection, item.path, destination)
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    _uiState.update { it.copy(isLoading = false, openUri = uri, openMimeType = item.mimeType) }
                }
                .onFailure { e ->
                    destination.delete()
                    _uiState.update { it.copy(isLoading = false, error = "Impossible d'ouvrir le fichier : ${e.message}") }
                }
        }
    }

    fun requestDownload(item: FileItem) {
        if (!item.isDirectory) _uiState.update { it.copy(downloadFile = item, error = null) }
    }
    fun dismissDownload() { _uiState.update { it.copy(downloadFile = null) } }
    fun download(destination: Uri? = null) {
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
}
