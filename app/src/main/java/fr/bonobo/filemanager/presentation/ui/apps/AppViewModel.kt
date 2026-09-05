package fr.bonobo.filemanager.presentation.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.bonobo.filemanager.domain.model.AppItem
import fr.bonobo.filemanager.domain.repository.IAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppUiState(
    val apps: List<AppItem> = emptyList(),
    val filteredApps: List<AppItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: IAppRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                repository.getInstalledApps()
            }.onSuccess { apps ->
                _uiState.update { it.copy(
                    apps = apps,
                    filteredApps = filterApps(apps, it.searchQuery),
                    isLoading = false
                ) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun refresh() = loadApps()

    fun search(query: String) {
        _uiState.update { it.copy(
            searchQuery = query,
            filteredApps = filterApps(it.apps, query)
        ) }
    }

    private fun filterApps(apps: List<AppItem>, query: String): List<AppItem> {
        if (query.isBlank()) return apps
        return apps.filter { 
            it.name.contains(query, ignoreCase = true) || 
            it.packageName.contains(query, ignoreCase = true) 
        }
    }

    fun backupApp(app: AppItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.backupApp(app, null)
                .onSuccess { path ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        message = "APK sauvegardé dans : $path"
                    ) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "Échec de la sauvegarde : ${e.message}"
                    ) }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
