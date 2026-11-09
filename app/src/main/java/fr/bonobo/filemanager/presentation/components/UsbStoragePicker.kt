package fr.bonobo.filemanager.presentation.components

import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import fr.bonobo.filemanager.util.SafFiles
import fr.bonobo.filemanager.util.UsbAccessScope

@Composable
fun UsbStoragePicker(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var selectingVolumeRoot by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val tree = data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && tree != null) {
            fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SAF_RESELECT,
                if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK
                else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED,
                storage = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SAF)
            fr.bonobo.filemanager.diagnostic.Diagnostic.event(
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SAF_PERMISSION,
                if (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK
                else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED)
            val flags = fr.bonobo.filemanager.util.IncomingFiles.grantedAccess(data.flags)
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) message = "Accès en lecture refusé. Sélectionnez à nouveau le dossier."
            else {
                if (fr.bonobo.filemanager.util.IncomingFiles.canPersist(data.flags)) {
                    runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
                        .onFailure { android.widget.Toast.makeText(context, "Accès temporaire : ce fournisseur ne conserve pas l'autorisation.", android.widget.Toast.LENGTH_LONG).show() }
                } else android.widget.Toast.makeText(context, "Accès temporaire : sélectionnez à nouveau le dossier après fermeture de l'application.", android.widget.Toast.LENGTH_LONG).show()
                onOpen(SafFiles.root(tree.toString()))
                onDismiss()
            }
        } else {
            fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SAF_RESELECT,
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.CANCELLED,
                storage = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SAF)
            message = if (selectingVolumeRoot)
                UsbAccessScope.ROOT_SELECTION_REJECTED
            else "Aucun emplacement sélectionné. Choisissez un dossier accessible dans le sélecteur Android."
        }
    }
    fun select(intent: Intent, volumeRoot: Boolean = false) {
        selectingVolumeRoot = volumeRoot
        fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SAF_RESELECT,
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.START,
            storage = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SAF)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        runCatching { launcher.launch(intent) }.onFailure { message = "Sélecteur de dossiers Android indisponible." }
    }
    val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && android.provider.DocumentsContract.isTreeUri(it.uri) }
    val volumes = context.getSystemService(StorageManager::class.java).storageVolumes.filter { !it.isPrimary && it.isRemovable }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Stockage USB / OTG") },
        text = { Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
            Text("Clés USB et autres volumes amovibles détectés par Android. La racine peut être refusée par le sélecteur Android ; vous pourrez alors choisir un dossier.")
            message?.let { Text(it) }
            Text("${volumes.size} volume(s) amovible(s), ${grants.size} emplacement(s) autorisé(s). Une autorisation enregistrée ne garantit pas que le fournisseur répond.")
            volumes.forEach { volume ->
                val rootGrant = grants.firstOrNull { grant ->
                    grant.uri.authority == "com.android.externalstorage.documents" &&
                        runCatching { UsbAccessScope.classify(android.provider.DocumentsContract.getTreeDocumentId(grant.uri), volume.uuid) == UsbAccessScope.Scope.VOLUME_ROOT }.getOrDefault(false)
                }
                Text("Clé USB / volume amovible : ${volume.getDescription(context)} — ${volume.state}")
                // Android 11+ with "all files access": the key is a normal mounted directory,
                // which avoids the Storage Access Framework limitations (slow, rename/trash restrictions).
                val directDirectory = if (Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())
                    volume.directory?.takeIf { it.canRead() } else null
                if (directDirectory != null) TextButton(onClick = {
                    onOpen(directDirectory.absolutePath); onDismiss()
                }) { Text("Ouvrir la clé (accès direct)") }
                if (rootGrant != null) TextButton(onClick = {
                    onOpen(SafFiles.root(rootGrant.uri.toString())); onDismiss()
                }) { Text("Ouvrir la racine autorisée") }
                TextButton(onClick = {
                    select(if (Build.VERSION.SDK_INT >= 29) volume.createOpenDocumentTreeIntent() else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), volumeRoot = true)
                }) { Text("Sélectionner la racine de la clé") }
            }
            grants.forEach { grant ->
                val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(grant.uri) }.getOrNull()
                val scope = if (grant.uri.authority == "com.android.externalstorage.documents" && treeId != null)
                    volumes.map { UsbAccessScope.classify(treeId, it.uuid) }.firstOrNull { it != UsbAccessScope.Scope.UNKNOWN }
                        ?: UsbAccessScope.Scope.UNKNOWN else UsbAccessScope.Scope.UNKNOWN
                TextButton(onClick = { onOpen(SafFiles.root(grant.uri.toString())); onDismiss() }) {
                    Text("${UsbAccessScope.label(scope)} : ${treeId ?: "identifiant inconnu"}${if (!grant.isWritePermission) " (lecture seule)" else ""}")
                }
            }
        } },
        confirmButton = { TextButton(onClick = { select(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }) { Text("Sélectionner un emplacement") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}
