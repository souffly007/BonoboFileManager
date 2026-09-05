package fr.bonobo.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.settingsDataStore
import fr.bonobo.filemanager.presentation.ui.navigation.AppNavigation
import fr.bonobo.filemanager.presentation.ui.theme.AppThemeMode
import fr.bonobo.filemanager.presentation.ui.theme.BonoboFileManagerTheme
import fr.bonobo.filemanager.util.PermissionUtils
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()

        val themeFlow = settingsDataStore.data
            .map { preferences ->
                val modeName = preferences[SettingsKeys.THEME_MODE] ?: AppThemeMode.SYSTEM.name
                try {
                    AppThemeMode.valueOf(modeName)
                } catch (e: Exception) {
                    AppThemeMode.SYSTEM
                }
            }

        setContent {
            val themeMode by themeFlow.collectAsState(initial = AppThemeMode.SYSTEM)

            BonoboFileManagerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavigation(onExit = { finish() })
                }
            }
        }
    }

    private fun checkPermissions() {
        if (!PermissionUtils.hasStoragePermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
