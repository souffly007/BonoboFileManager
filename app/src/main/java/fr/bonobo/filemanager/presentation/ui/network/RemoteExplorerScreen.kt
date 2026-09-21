package fr.bonobo.filemanager.presentation.ui.network

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.components.FileTypeIcon
import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.service.RemoteDownloadService
import fr.bonobo.filemanager.util.FileUtils
import fr.bonobo.filemanager.util.verticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteExplorerScreen(
    viewModel: RemoteViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val transfer by RemoteDownloadService.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        choosing = false
        if (uri == null) viewModel.dismissDownload()
        else {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.download(uri)
        }
    }
    LaunchedEffect(state.openUri) {
        state.openUri?.let { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, state.openMimeType ?: "application/octet-stream")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Aucune application ne peut ouvrir ce fichier", android.widget.Toast.LENGTH_LONG).show()
            } finally { viewModel.consumeOpenUri() }
        }
    }
    state.downloadFile?.takeIf { !choosing }?.let { item ->
        AlertDialog(onDismissRequest = viewModel::dismissDownload,
            title = { Text("Télécharger") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name)
                Text(FileUtils.formatSize(item.size))
                Text("Destination par défaut : Téléchargements. Vous pouvez aussi choisir un autre dossier.")
                Text("En cas d'interruption, relancez le téléchargement depuis le début.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !transfer.active, onClick = { viewModel.download() }) { Text("Téléchargements") } },
            dismissButton = { Column {
                TextButton(enabled = !transfer.active, onClick = {
                    choosing = true
                    picker.launch(item.name)
                }) { Text("Choisir ailleurs…") }
                TextButton(onClick = viewModel::dismissDownload) { Text("Annuler") }
            } })
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.connection?.name ?: "Explorateur distant", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.currentPath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = {
            IconButton(onClick = if (state.currentPath == "/") onNavigateBack else viewModel::navigateUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
            }
        }, actions = { IconButton(onClick = onNavigateHome) { Icon(Icons.Default.Home, "Accueil") } })
    }) { padding ->
        PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (transfer.active || transfer.message != null) {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(transfer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            if (transfer.active) {
                                if (transfer.total > 0) LinearProgressIndicator(
                                    progress = { (transfer.bytes.toDouble() / transfer.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("${FileUtils.formatSize(transfer.bytes)} / ${FileUtils.formatSize(transfer.total.coerceAtLeast(0))}")
                                TextButton(onClick = { RemoteDownloadService.cancel(context) }) { Text("Annuler le téléchargement") }
                            }
                            transfer.message?.let { Text(it, color = if (transfer.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                LazyColumn(Modifier.weight(1f).verticalScrollbar(listState), state = listState) {
                    items(state.files, key = { it.path }) { item ->
                        var menu by remember { mutableStateOf(false) }
                        var deleting by remember { mutableStateOf(false) }
                        if (deleting) AlertDialog(onDismissRequest = { deleting = false },
                            title = { Text("Supprimer l'élément distant") },
                            text = { Text("Supprimer « ${item.name} » du serveur ? Cette action est irréversible.") },
                            confirmButton = { TextButton(onClick = { deleting = false; viewModel.deleteFile(item) }) { Text("Supprimer") } },
                            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } })
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth().clickable {
                                if (item.isDirectory) {
                                    viewModel.open(item)
                                } else if (!transfer.active) {
                                    if (state.connection?.type == ConnectionType.SMB) {
                                        viewModel.open(item)
                                    } else {
                                        viewModel.requestDownload(item)
                                    }
                                }
                            }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FileTypeIcon(item, size = 44.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!item.isDirectory) Text(FileUtils.formatSize(item.size), style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options de ${item.name}") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (item.isDirectory) DropdownMenuItem(text = { Text("Ouvrir le dossier") },
                                            onClick = { menu = false; viewModel.open(item) })
                                        else {
                                        if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB)
                                            DropdownMenuItem(text = { Text("Ouvrir") }, enabled = !transfer.active,
                                                onClick = { menu = false; viewModel.open(item) })
                                        DropdownMenuItem(text = { Text("Télécharger") },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }, enabled = !transfer.active,
                                            onClick = { menu = false; viewModel.requestDownload(item) })
                                        }
                                        DropdownMenuItem(text = { Text("Supprimer du serveur") }, enabled = !transfer.active,
                                            onClick = { menu = false; deleting = true })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
