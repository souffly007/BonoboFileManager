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
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) android.widget.Toast.makeText(this, "Notifications désactivées. Les transferts restent disponibles ; leur état est visible dans Bonobo.", android.widget.Toast.LENGTH_LONG).show()
    }
    private val received = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var lastGrantState: fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        handleDropboxIntent(intent)
        handleOneDriveIntent(intent)
        handleFiles(intent)
        // Not on recreation (rotation…): the same attach intent would be handled twice.
        if (savedInstanceState == null) handleUsbAttached(intent)

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
                    AppNavigation(onExit = { finish() }, receivedPath = received.value, onReceivedConsumed = { received.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDropboxIntent(intent)
        handleOneDriveIntent(intent)
        handleFiles(intent)
        handleUsbAttached(intent)
    }

    /**
     * Key plugged in and Bonobo chosen in the system dialog. The volume is usually not mounted yet:
     * wait for it (max 10 s), then open the root of the removable volume (most recent if several).
     * launchMode stays singleTop: an already visible Bonobo receives the intent in onNewIntent, and
     * « Ouvrir avec » / partage keep opening in the caller's task as before.
     */
    private var usbWait: kotlinx.coroutines.Job? = null
    private fun handleUsbAttached(incoming: Intent?) {
        if (incoming?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_ATTACH_INTENT)
        // Consume it: a later recreation or onResume must not replay the attach.
        setIntent(Intent(Intent.ACTION_MAIN))
        val intentTime = System.currentTimeMillis()
        val before = fr.bonobo.filemanager.util.UsbVolumes.mountedRemovable(this)
        usbWait?.cancel()
        usbWait = lifecycleScope.launch {
            val operation = fr.bonobo.filemanager.diagnostic.Diagnostic.operation()
            fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_VOLUME_WAIT,
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.START, operation)
            val wait = runCatching {
                fr.bonobo.filemanager.util.UsbVolumes.awaitPluggedVolume(this@MainActivity, intentTime, before)
            }.getOrNull()
            val volume = wait?.volume ?: wait?.fallback
            fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_VOLUME_WAIT,
                if (wait?.volume != null) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK
                else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.TIMEOUT, operation, durationMs = wait?.waitedMs)
            val directory = volume?.directory
            if (directory == null) {
                fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_AUTO_OPEN,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.TIMEOUT, operation)
                android.widget.Toast.makeText(this@MainActivity,
                    "Clé USB détectée, mais Android ne l’a pas rendue accessible en 10 secondes. " +
                        "Vérifiez qu’elle est formatée (FAT32, exFAT ou NTFS) puis ouvrez-la via « USB / OTG ».",
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!PermissionUtils.hasStoragePermission(this@MainActivity)) {
                fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_AUTO_OPEN,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.PERMISSION_DENIED, operation)
                android.widget.Toast.makeText(this@MainActivity,
                    "Clé USB prête. Autorisez l’accès à tous les fichiers, ou ouvrez-la via « USB / OTG ».",
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_AUTO_OPEN,
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK, operation, durationMs = wait?.waitedMs)
            received.value = directory.path
            android.widget.Toast.makeText(this@MainActivity,
                "Ouverture de ${volume.label ?: "la clé USB"}", android.widget.Toast.LENGTH_SHORT).show()
        }
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


    private fun handleFiles(incoming: Intent?) {
        if (incoming == null || incoming.action !in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) || incoming.data?.scheme in listOf("msauth", "db-a4pj0x994ilv7o6")) return
        lifecycleScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    fr.bonobo.filemanager.util.IncomingFiles.receive(this@MainActivity, incoming)
                }
            }.onSuccess { files ->
                if (files.isNotEmpty()) android.widget.Toast.makeText(this@MainActivity, "Ouverture d'une copie temporaire. Les modifications ne changent pas le fichier d'origine.", android.widget.Toast.LENGTH_LONG).show()
                if (files.size == 1) received.value = files.first().path
                else if (files.isNotEmpty()) {
                    val folder = java.io.File(cacheDir, "received/shared-${java.util.UUID.randomUUID()}").apply { mkdirs() }
                    files.forEachIndexed { index, file -> check(file.renameTo(java.io.File(folder, "$index-${file.name}"))) }
                    received.value = folder.path
                }
            }.onFailure { android.widget.Toast.makeText(this@MainActivity, "Fichier reçu inaccessible : ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun checkPermissions() {
        if (intent?.action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
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
        runCatching {
            if (fr.bonobo.filemanager.diagnostic.Diagnostic.journal?.enabled == true) {
                fr.bonobo.filemanager.diagnostic.Diagnostic.event(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.APP_RESUMED)
                // Previously REFUSED was written at every resume whenever no persisted grant was
                // read+write — including the normal case of zero SAF grants (USB opened in direct
                // access). Now: NO_PERSISTED_GRANT when there is none, REFUSED only for read-only
                // grants, and a line only when the state changes.
                val grants = contentResolver.persistedUriPermissions
                val result = when {
                    grants.isEmpty() -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.NO_PERSISTED_GRANT
                    grants.any { it.isReadPermission && it.isWritePermission } -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK
                    else -> fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED
                }
                if (result != lastGrantState) {
                    lastGrantState = result
                    fr.bonobo.filemanager.diagnostic.Diagnostic.event(
                        fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SAF_PERMISSION, result)
                }
            }
        }
        val prefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && intent?.action == Intent.ACTION_MAIN &&
            PermissionUtils.hasStoragePermission(this) && !prefs.getBoolean("requested", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("requested", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
