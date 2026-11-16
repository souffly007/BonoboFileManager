package fr.bonobo.filemanager.presentation.ui.cleaner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CleanerUiState(
    val junkFiles: List<FileItem> = emptyList(),
    val emptyFolders: List<FileItem> = emptyList(),
    val isScanning: Boolean = false,
    val isCleaning: Boolean = false,
    val totalJunkSize: Long = 0,
    val selectedJunkPaths: Set<String> = emptySet(),
    val selectedFolderPaths: Set<String> = emptySet(),
    val scanCompleted: Boolean = false,
    val cleanCompleted: Boolean = false,
    val freedSpace: Long = 0
)

@HiltViewModel
class CleanerViewModel @Inject constructor(
    private val repository: IFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanerUiState())
    val uiState: StateFlow<CleanerUiState> = _uiState.asStateFlow()

    fun startScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanCompleted = false, cleanCompleted = false) }
            
            val junk = repository.getJunkFiles()
            val folders = repository.getEmptyFolders()
            
            val totalSize = junk.sumOf { it.size }
            
            _uiState.update { it.copy(
                junkFiles = junk,
                emptyFolders = folders,
                totalJunkSize = totalSize,
                selectedJunkPaths = junk.map { f -> f.path }.toSet(),
                selectedFolderPaths = folders.map { f -> f.path }.toSet(),
                isScanning = false,
                scanCompleted = true
            ) }
        }
    }

    fun toggleJunkSelection(path: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedJunkPaths.contains(path)) {
                state.selectedJunkPaths - path
            } else {
                state.selectedJunkPaths + path
            }
            state.copy(selectedJunkPaths = newSelection)
        }
    }

    fun toggleFolderSelection(path: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedFolderPaths.contains(path)) {
                state.selectedFolderPaths - path
            } else {
                state.selectedFolderPaths + path
            }
            state.copy(selectedFolderPaths = newSelection)
        }
    }

    fun clean() {
        val pathsToClean = _uiState.value.selectedJunkPaths + _uiState.value.selectedFolderPaths
        val sizeToFree = _uiState.value.junkFiles
            .filter { _uiState.value.selectedJunkPaths.contains(it.path) }
            .sumOf { it.size }

        viewModelScope.launch {
            _uiState.update { it.copy(isCleaning = true) }
            repository.deleteFiles(pathsToClean.toList())
                .onSuccess {
                    _uiState.update { it.copy(
                        isCleaning = false,
                        cleanCompleted = true,
                        freedSpace = sizeToFree,
                        junkFiles = emptyList(),
                        emptyFolders = emptyList(),
                        totalJunkSize = 0,
                        scanCompleted = false
                    ) }
                }
        }
    }
}
