package fr.bonobo.filemanager.presentation.ui.vault

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.ui.Alignment
import androidx.fragment.app.FragmentActivity
import fr.bonobo.filemanager.util.BiometricUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    var confirmLegacy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val fragmentActivity = context as? FragmentActivity
    val canBiometric = remember(context) { BiometricUtils.canAuthenticate(context) }
    DisposableEffect(owner, activity) {
        val alreadySecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                password = ""
                confirmation = ""
                deleteFile = null
                confirmLegacy = false
                viewModel.lock()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            viewModel.lock()
            if (!alreadySecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importDocument(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exportFile
        exportFile = null
        if (uri != null && file != null) viewModel.exportDocument(file, uri)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Coffre-fort") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        }, actions = {
            if (state.unlocked) TextButton(onClick = { viewModel.lock() }) { Text("Verrouiller") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Les fichiers importés sont chiffrés dans l'espace privé de l'application. " +
                    "Conservez votre mot de passe et une copie de secours : désinstaller l'application supprime ce coffre.")
            }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            if (state.busy || state.configured == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.unlocked) {
                item {
                    OutlinedTextField(password, { password = it }, enabled = !state.busy,
                        label = { Text(if (state.configured == false) "Créer le mot de passe (8 caractères minimum)" else "Mot de passe maître") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (state.configured == false) item {
                    OutlinedTextField(confirmation, { confirmation = it }, enabled = !state.busy,
                        label = { Text("Confirmer le mot de passe") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (state.configured == true && state.biometricEnabled && canBiometric && fragmentActivity != null) {
                    item {
                        OutlinedButton(enabled = !state.busy, onClick = {
                            BiometricUtils.showBiometricPrompt(
                                activity = fragmentActivity,
                                title = "Ouvrir le coffre-fort",
                                subtitle = "Utilisez votre empreinte ou le code de l'appareil",
                                onSuccess = { viewModel.unlockWithBiometric() },
                                onError = { /* Le message système explique l'échec. */ }
                            )
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Déverrouiller avec le doigt")
                        }
                    }
                }
                item {
                    Button(enabled = !state.busy && state.configured != null, onClick = {
                        viewModel.unlock(password, confirmation)
                        password = ""
                        confirmation = ""
                    }) { Text(if (state.configured == false) "Créer le coffre" else "Déverrouiller") }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Déverrouillage avec le doigt", style = MaterialTheme.typography.titleSmall)
                            Text("Proposer la biométrie à la prochaine ouverture", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = state.biometricEnabled,
                            onCheckedChange = { viewModel.setBiometricEnabled(it) },
                            enabled = canBiometric && !state.busy
                        )
                    }
                    Button(enabled = !state.busy, onClick = { importer.launch(arrayOf("*/*")) }) {
                        Text("Rechercher un fichier")
                    }
                    Text("L'importation conserve l'original. L'exportation crée une copie en clair.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !state.busy, onClick = { confirmLegacy = true }) {
                        Text("Récupérer l'ancien coffre")
                    }
                }
                items(state.files, key = { it.absolutePath }) { file ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.name.removeSuffix(".crypt"))
                            Row {
                                TextButton(enabled = !state.busy, onClick = {
                                    exportFile = file
                                    exporter.launch(file.name.removeSuffix(".crypt"))
                                }) { Text("Exporter une copie") }
                                TextButton(enabled = !state.busy, onClick = { deleteFile = file }) { Text("Supprimer") }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteFile?.let { file ->
        AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("Supprimer du coffre ?") },
            text = { Text("Cette suppression est définitive. Vérifiez que vous avez une copie si nécessaire.") },
            confirmButton = { TextButton(onClick = { deleteFile = null; viewModel.delete(file) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Annuler") } })
    }
    if (confirmLegacy) AlertDialog(onDismissRequest = { confirmLegacy = false }, title = { Text("Récupérer l'ancien coffre") },
        text = { Text("Les fichiers de .bonobo_vault seront copiés et chiffrés. Les anciens fichiers resteront en place. " +
            "Vérifiez les copies avant de supprimer les originaux. Relancer l'importation crée des doublons.") },
        confirmButton = { TextButton(onClick = { confirmLegacy = false; viewModel.importLegacy() }) { Text("Copier et chiffrer") } },
        dismissButton = { TextButton(onClick = { confirmLegacy = false }) { Text("Annuler") } })
}
