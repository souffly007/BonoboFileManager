package fr.bonobo.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import fr.bonobo.filemanager.service.DropboxCloudManager
import fr.bonobo.filemanager.service.OneDriveCloudManager
import fr.bonobo.filemanager.service.GoogleDriveCloudManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        handleDropboxIntent(intent)
        handleOneDriveIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDropboxIntent(intent)
        handleOneDriveIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GoogleDriveCloudManager.REQUEST_CODE) {
            GoogleDriveCloudManager.handleSignInResult(this, data)
        }
    }

    private fun handleDropboxIntent(intent: Intent?) {
        if (intent?.data?.scheme == "db-a4pj0x994ilv7o6") {
            lifecycleScope.launch { DropboxCloudManager.handleCallback(this@MainActivity, intent.data!!) }
        }
    }

    private fun handleOneDriveIntent(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "msauth" && data.host == "fr.bonobo.filemanager.https.preview") {
            lifecycleScope.launch { OneDriveCloudManager.handleCallback(this@MainActivity, data) }
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
