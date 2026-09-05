package fr.bonobo.filemanager.presentation.ui.network

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
    val error: String? = null
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remoteRepository: fr.bonobo.filemanager.data.repository.RemoteFileRepository
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
        }
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
