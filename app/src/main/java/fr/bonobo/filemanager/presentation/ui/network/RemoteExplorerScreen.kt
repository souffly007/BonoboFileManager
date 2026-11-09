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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.components.FileTypeIcon
import fr.bonobo.filemanager.service.RemoteDownloadService
import fr.bonobo.filemanager.util.FileUtils
import fr.bonobo.filemanager.util.verticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteExplorerScreen(
    viewModel: RemoteViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenText: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenOfficeDocument: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenAudio: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val managedTransfer by viewModel.transfers.collectAsStateWithLifecycle()
    val transfer by RemoteDownloadService.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.internalOpenPath, state.internalOpenKind) {
        val path = state.internalOpenPath ?: return@LaunchedEffect
        when (state.internalOpenKind) {
            "text" -> onOpenText(path)
            "pdf" -> onOpenPdf(path)
            "office" -> onOpenOfficeDocument(path)
            "video" -> onOpenVideo(path)
            "audio" -> onOpenAudio(path)
        }
        viewModel.consumeInternalOpen()
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var favoriteMenu by remember { mutableStateOf(false) }
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
            confirmButton = { TextButton(enabled = !transfer.active && !managedTransfer.active, onClick = { viewModel.download() }) { Text("Téléchargements") } },
            dismissButton = { Column {
                TextButton(enabled = !transfer.active && !managedTransfer.active, onClick = {
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
        }, actions = {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Trier") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DropdownMenuItem(text = { Text("Nom" + if (state.sort == RemoteSort.NAME) if (state.sortAscending) " ↑" else " ↓" else "") }, onClick = { sortMenu = false; viewModel.setSort(RemoteSort.NAME) })
                    DropdownMenuItem(text = { Text("Date" + if (state.sort == RemoteSort.DATE) if (state.sortAscending) " ↑" else " ↓" else "") }, onClick = { sortMenu = false; viewModel.setSort(RemoteSort.DATE) })
                    DropdownMenuItem(text = { Text("Taille" + if (state.sort == RemoteSort.SIZE) if (state.sortAscending) " ↑" else " ↓" else "") }, onClick = { sortMenu = false; viewModel.setSort(RemoteSort.SIZE) })
                }
            }
            Box {
                IconButton(onClick = { favoriteMenu = true }) {
                    Icon(if (state.currentPath in state.favoritePaths) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Favoris réseau")
                }
                DropdownMenu(expanded = favoriteMenu, onDismissRequest = { favoriteMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (state.currentPath in state.favoritePaths) "Retirer ce dossier des favoris" else "Ajouter ce dossier aux favoris") },
                        onClick = { favoriteMenu = false; viewModel.toggleFavoriteCurrentPath() }
                    )
                    if (state.favoritePaths.isNotEmpty()) HorizontalDivider()
                    state.favoritePaths.sorted().forEach { path ->
                        DropdownMenuItem(text = { Text(path) }, onClick = { favoriteMenu = false; viewModel.openFavorite(path) })
                    }
                }
            }
            if (state.clipboard != null && state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB) {
                IconButton(onClick = viewModel::pasteHere, enabled = !state.isLoading && !managedTransfer.active) {
                    Icon(Icons.Default.ContentPaste, "Coller ici")
                }
            }
            IconButton(onClick = onNavigateHome) { Icon(Icons.Default.Home, "Accueil") }
        })
    }) { padding ->
        PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (state.selectedPaths.isNotEmpty()) {
                    val canTransfer = state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB || state.files.none { it.path in state.selectedPaths && it.isDirectory }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.selectedPaths.size} sélectionnés", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { viewModel.prepareSelected(false) }, enabled = canTransfer && !managedTransfer.active) { Icon(Icons.Default.ContentCopy, "Copier la sélection") }
                            IconButton(onClick = { viewModel.prepareSelected(true) }, enabled = canTransfer && !managedTransfer.active) { Icon(Icons.Default.ContentCut, "Déplacer la sélection") }
                            IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, "Annuler la sélection") }
                        }
                    }
                }
                fr.bonobo.filemanager.presentation.components.TransferPanel(managedTransfer, viewModel::cancelTransfer, viewModel::dismissTransfer)
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
                state.message?.let { message ->
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(2500)
                        viewModel.clearMessage()
                    }
                    Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                state.clipboard?.let { clipboard ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (clipboard.operation == RemoteOperation.MOVE) Icons.Default.DriveFileMove else Icons.Default.FileCopy, null)
                            Text(
                                "${when (clipboard.operation) { RemoteOperation.MOVE -> "Déplacer"; RemoteOperation.DUPLICATE -> "Dupliquer"; RemoteOperation.COPY -> "Copier" }} : ${clipboard.items.size} élément(s)",
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB)
                                TextButton(onClick = viewModel::pasteHere, enabled = !state.isLoading && !managedTransfer.active) { Text("Mettre ici") }
                            else TextButton(onClick = onNavigateHome) { Text("Dossier local") }
                            IconButton(onClick = viewModel::cancelClipboard) { Icon(Icons.Default.Close, "Annuler") }
                        }
                    }
                }
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
                                if (item.isDirectory) viewModel.open(item) else if (!transfer.active && !managedTransfer.active) {
                                    if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.FTPS || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SFTP || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.WEBDAV) viewModel.open(item)
                                    else viewModel.requestDownload(item)
                                }
                            }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.path in state.selectedPaths, onCheckedChange = { viewModel.toggleSelection(item.path) }, enabled = !managedTransfer.active)
                                val thumb = state.thumbnails[item.path]
                                LaunchedEffect(item.path) { viewModel.requestThumbnail(item) }
                                Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                                    FileTypeIcon(item, size = 44.dp)
                                    if (thumb != null) AsyncImage(
                                        model = ImageRequest.Builder(context).data(java.io.File(thumb)).crossfade(true).build(),
                                        contentDescription = "Miniature de ${item.name}",
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                    )
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!item.isDirectory) Text(FileUtils.formatSize(item.size), style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options de ${item.name}") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (item.isDirectory) {
                                            DropdownMenuItem(text = { Text("Ouvrir le dossier") },
                                                onClick = { menu = false; viewModel.open(item) })
                                            DropdownMenuItem(
                                                text = { Text(if (item.path in state.favoritePaths) "Retirer des favoris" else "Ajouter aux favoris") },
                                                leadingIcon = { Icon(if (item.path in state.favoritePaths) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                                                onClick = { menu = false; viewModel.toggleFavorite(item) }
                                            )
                                        } else {
                                        if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.FTPS || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SFTP || state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.WEBDAV)
                                            DropdownMenuItem(text = { Text("Ouvrir") }, enabled = !transfer.active && !managedTransfer.active,
                                                onClick = { menu = false; viewModel.open(item) })
                                        DropdownMenuItem(text = { Text("Télécharger") },
                                            leadingIcon = { Icon(Icons.Default.Download, null) }, enabled = !transfer.active && !managedTransfer.active,
                                            onClick = { menu = false; viewModel.requestDownload(item) })
                                        }
                                        if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB ||
                                            (!item.isDirectory && state.connection?.type in listOf(fr.bonobo.filemanager.domain.model.ConnectionType.SFTP, fr.bonobo.filemanager.domain.model.ConnectionType.WEBDAV, fr.bonobo.filemanager.domain.model.ConnectionType.FTPS))) {
                                            DropdownMenuItem(
                                                text = { Text(if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB) "Copier" else "Copier vers local") },
                                                leadingIcon = { Icon(Icons.Default.FileCopy, null) },
                                                enabled = !transfer.active && !managedTransfer.active,
                                                onClick = { menu = false; viewModel.prepareCopy(item) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB) "Déplacer" else "Déplacer vers local") },
                                                leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                                                enabled = !transfer.active && !managedTransfer.active,
                                                onClick = { menu = false; viewModel.prepareMove(item) }
                                            )
                                            if (state.connection?.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB) DropdownMenuItem(
                                                text = { Text("Dupliquer") },
                                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                                enabled = !transfer.active && !managedTransfer.active,
                                                onClick = { menu = false; viewModel.prepareDuplicate(item) }
                                            )
                                        }
                                        DropdownMenuItem(text = { Text("Supprimer du serveur") }, enabled = !transfer.active && !managedTransfer.active,
                                            leadingIcon = { Icon(Icons.Default.Delete, null) },
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
