package fr.bonobo.filemanager.presentation.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.settingsDataStore
import fr.bonobo.filemanager.presentation.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    val showHiddenFiles = context.settingsDataStore.data
        .map { preferences ->
            preferences[SettingsKeys.SHOW_HIDDEN_FILES] ?: false
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    val themeMode = context.settingsDataStore.data
        .map { preferences ->
            val modeName = preferences[SettingsKeys.THEME_MODE] ?: AppThemeMode.SYSTEM.name
            try {
                AppThemeMode.valueOf(modeName)
            } catch (_: Exception) {
                AppThemeMode.SYSTEM
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialValue = AppThemeMode.SYSTEM
        )

    val rootAccess = context.settingsDataStore.data
        .map { preferences ->
            preferences[SettingsKeys.ROOT_ACCESS] ?: false
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            false
        )

    val thumbnailSize = context.settingsDataStore.data
        .map { preferences ->
            preferences[SettingsKeys.THUMBNAIL_SIZE] ?: "MEDIUM"
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            "MEDIUM"
        )

    val vaultPassword = context.settingsDataStore.data
        .map { preferences ->
            preferences[SettingsKeys.VAULT_PASSWORD]
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )

    fun setShowHiddenFiles(value: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.SHOW_HIDDEN_FILES] = value
            }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.THEME_MODE] = mode.name
            }
        }
    }

    fun setRootAccess(value: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.ROOT_ACCESS] = value
            }
        }
    }

    fun setThumbnailSize(size: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.THUMBNAIL_SIZE] = size
            }
        }
    }

    fun setVaultPassword(password: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { preferences ->
                preferences[SettingsKeys.VAULT_PASSWORD] = password
            }
        }
    }
}
