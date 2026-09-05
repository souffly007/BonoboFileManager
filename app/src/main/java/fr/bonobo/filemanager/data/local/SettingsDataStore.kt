package fr.bonobo.filemanager.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
    val SORT_MODE = stringPreferencesKey("sort_mode")
    val IS_SORT_ASCENDING = booleanPreferencesKey("is_sort_ascending")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ROOT_ACCESS = booleanPreferencesKey("root_access")
    val THUMBNAIL_SIZE = stringPreferencesKey("thumbnail_size")
    val VAULT_PASSWORD = stringPreferencesKey("vault_password")
}
