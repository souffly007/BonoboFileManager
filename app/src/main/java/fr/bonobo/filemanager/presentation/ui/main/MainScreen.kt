package fr.bonobo.filemanager.presentation.ui.main

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.presentation.components.BreadcrumbNav
import fr.bonobo.filemanager.presentation.components.FileItemCard
import fr.bonobo.filemanager.presentation.components.SearchBar
import fr.bonobo.filemanager.presentation.components.StorageBar
import fr.bonobo.filemanager.presentation.ui.operations.CompressDialog
import fr.bonobo.filemanager.presentation.ui.operations.CreateFileDialog
import fr.bonobo.filemanager.presentation.ui.operations.CreateFolderDialog
import fr.bonobo.filemanager.presentation.ui.operations.DeleteDialog
import fr.bonobo.filemanager.presentation.ui.operations.MultiDeleteDialog
import fr.bonobo.filemanager.presentation.ui.operations.MultiRenameDialog
import fr.bonobo.filemanager.presentation.ui.operations.EncryptionDialog
import fr.bonobo.filemanager.presentation.ui.operations.RenameDialog
import fr.bonobo.filemanager.util.ShareUtils
import fr.bonobo.filemanager.util.verticalScrollbar
import fr.bonobo.filemanager.util.verticalGridScrollbar
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    onOpenVideo: (String) -> Unit = {},
    onOpenAudio: (String) -> Unit = {},
    onOpenText: (String) -> Unit = {},
    onOpenTransfer: (String) -> Unit = {},
    onOpenDiff: (String, String) -> Unit = { _, _ -> }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToRename by remember { mutableStateOf<FileItem?>(null) }
    var itemToDelete by remember { mutableStateOf<FileItem?>(null) }
    var itemToCompress by remember { mutableStateOf<FileItem?>(null) }
    var itemToEncrypt by remember { mutableStateOf<FileItem?>(null) }
    var itemToDecrypt by remember { mutableStateOf<FileItem?>(null) }
    var showMultiDeleteDialog by remember { mutableStateOf(false) }
    var showMultiRenameDialog by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(value = false) }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }

    val isSelectionMode = state.selectedPaths.isNotEmpty()
    val isTrashView = state.categoryName == "Corbeille"

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    BackHandler(enabled = isSelectionMode || !viewModel.isAtRoot()) {
        if (isSelectionMode) {
            viewModel.clearSelection()
        } else {
            viewModel.goUp()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun handleOption(option: String, item: FileItem) {
        when (option) {
            "FAVORITE" -> viewModel.toggleFavorite(item)
            "COPY" -> viewModel.setClipboard(item, isMove = false)
            "MOVE" -> viewModel.setClipboard(item, isMove = true)
            "RENAME" -> itemToRename = item
            "DELETE" -> itemToDelete = item
            "SHARE" -> ShareUtils.shareFile(context, File(item.path), item.mimeType)
            "COMPRESS" -> itemToCompress = item
            "EXTRACT" -> viewModel.setPendingExtraction(item)
            "RESTORE" -> viewModel.restoreFromTrash(item)
            "ENCRYPT" -> itemToEncrypt = item
            "DECRYPT" -> itemToDecrypt = item
            "TRANSFER" -> onOpenTransfer(item.path)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedPaths.size} sélectionnés") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Annuler")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMultiRenameDialog = true }) {
                            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Renommer")
                        }
                        if (state.selectedPaths.size == 2) {
                            IconButton(onClick = { 
                                val paths = state.selectedPaths.toList()
                                onOpenDiff(paths[0], paths[1])
                            }) {
                                Icon(Icons.Default.Difference, contentDescription = "Comparer")
                            }
                        }
                        IconButton(onClick = { showMultiDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                        }
                    }
                )
            } else {
                MediumTopAppBar(
                    title = { 
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = state.categoryName ?: if (viewModel.isAtRoot()) "Bonobo" else state.currentPath.split("/").last()
                            )
                            if (viewModel.isAtRoot() && state.categoryName == null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "🦍",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (!viewModel.isAtRoot()) {
                            IconButton(onClick = viewModel::goUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                            }
                        } else {
                            IconButton(onClick = onNavigateHome) {
                                Icon(Icons.Default.Home, contentDescription = "Dashboard")
                            }
                        }
                    },
                    actions = {
                        if (isTrashView) {
                            IconButton(onClick = viewModel::emptyTrash) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Vider la corbeille")
                            }
                        }
                        IconButton(onClick = viewModel::toggleViewMode) {
                            Icon(
                                if (state.isGridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                                contentDescription = "Vue"
                            )
                        }
                        IconButton(onClick = viewModel::toggleDualPane) {
                            Icon(Icons.Default.VerticalSplit, contentDescription = "Double panneau")
                        }
                        if (!viewModel.isAtRoot() && state.categoryName == null) {
                            IconButton(onClick = viewModel::addBookmark) {
                                Icon(Icons.Default.BookmarkBorder, contentDescription = "Épingler")
                            }
                        }
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Trier")
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                SortMenuItem(
                                    text = "Nom",
                                    isSelected = state.sortMode == SortMode.NAME,
                                    isAscending = state.isSortAscending,
                                    onClick = { viewModel.setSortMode(SortMode.NAME); sortMenuExpanded = false }
                                )
                                SortMenuItem(
                                    text = "Taille",
                                    isSelected = state.sortMode == SortMode.SIZE,
                                    isAscending = state.isSortAscending,
                                    onClick = { viewModel.setSortMode(SortMode.SIZE); sortMenuExpanded = false }
                                )
                                SortMenuItem(
                                    text = "Date",
                                    isSelected = state.sortMode == SortMode.DATE,
                                    isAscending = state.isSortAscending,
                                    onClick = { viewModel.setSortMode(SortMode.DATE); sortMenuExpanded = false }
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                state.pendingExtraction?.let {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.extractToCurrentPath() },
                        icon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        text = { Text("Extraire ici") },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                }

                state.pendingAppBackup?.let {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.backupAppToCurrentPath() },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        text = { Text("Backup APK ici") },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                state.clipboard?.let {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.pasteClipboard() },
                        icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                        text = { Text("Coller ici") }
                    )
                }

                if (!isSelectionMode && !isTrashView && !viewModel.isAtRoot()) {
                    Box {
                        FloatingActionButton(
                            onClick = { createMenuExpanded = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Créer")
                        }
                        DropdownMenu(
                            expanded = createMenuExpanded,
                            onDismissRequest = { createMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Nouveau dossier") },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                onClick = {
                                    createMenuExpanded = false
                                    showCreateFolderDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Nouveau fichier") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                                onClick = {
                                    createMenuExpanded = false
                                    showCreateFileDialog = true
                                }
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = !isSelectionMode) {
                        SearchBar(
                            query = state.searchQuery,
                            onQueryChange = viewModel::search
                        )
                    }

                    if (!viewModel.isAtRoot() && !isSelectionMode) {
                        BreadcrumbNav(
                            path = state.currentPath,
                            rootPath = state.rootPath,
                            onNavigate = viewModel::loadFiles
                        )
                    }

                    state.pendingExtraction?.let { extraction ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Extraction : ${extraction.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            IconButton(onClick = viewModel::clearPendingExtraction) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Annuler",
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    state.pendingAppBackup?.let { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Backup de : ${app.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            IconButton(onClick = viewModel::clearPendingAppBackup) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Annuler",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    state.clipboard?.let { clipboard ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Prêt à coller : ${clipboard.file.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            IconButton(onClick = viewModel::clearClipboard) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Annuler",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    if (isTrashView && state.files.isNotEmpty()) {
                        Button(
                            onClick = viewModel::emptyTrash,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Vider la corbeille d'un coup")
                        }
                    }

                    state.error?.let { error ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onNavigateHome) {
                                Icon(Icons.Default.Home, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retour à l'accueil")
                            }
                        }
                    }

                    if (state.isDualPane) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Panneau 1
                            Box(modifier = Modifier.weight(1f).clickable { viewModel.setActivePanel(1) }) {
                                FileExplorerPanel(
                                    state = state,
                                    onNavigate = { viewModel.setActivePanel(1); viewModel.loadFiles(it) },
                                    onItemClick = { item ->
                                        viewModel.setActivePanel(1)
                                        if (isSelectionMode) viewModel.toggleSelection(item.path)
                                        else if (isTrashView) itemToDelete = item
                                        else {
                                            if (item.isDirectory) viewModel.open(item)
                                            else openItem(context, item, onOpenImage, onOpenVideo, onOpenAudio, onOpenText)
                                        }
                                    },
                                    onItemLongClick = { viewModel.setActivePanel(1); viewModel.toggleSelection(it.path) },
                                    onItemOption = { item, opt ->
                                        viewModel.setActivePanel(1)
                                        handleOption(opt, item)
                                    },
                                    modifier = Modifier.background(if (state.activePanel == 1) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else Color.Transparent)
                                )
                            }
                            
                            VerticalDivider()
                            
                            // Panneau 2
                            Box(modifier = Modifier.weight(1f).clickable { viewModel.setActivePanel(2) }) {
                                FileExplorerPanel(
                                    state = state.copy(
                                        currentPath = state.secondPath ?: state.rootPath,
                                        files = state.secondFiles,
                                        isLoading = state.isSecondLoading
                                    ),
                                    onNavigate = { viewModel.setActivePanel(2); viewModel.loadFiles(it) },
                                    onItemClick = { item ->
                                        viewModel.setActivePanel(2)
                                        if (isSelectionMode) viewModel.toggleSelection(item.path)
                                        else {
                                            if (item.isDirectory) viewModel.loadFiles(item.path)
                                            else openItem(context, item, onOpenImage, onOpenVideo, onOpenAudio, onOpenText)
                                        }
                                    },
                                    onItemLongClick = { viewModel.setActivePanel(2); viewModel.toggleSelection(it.path) },
                                    onItemOption = { item, opt ->
                                        viewModel.setActivePanel(2)
                                        handleOption(opt, item)
                                    },
                                    modifier = Modifier.background(if (state.activePanel == 2) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else Color.Transparent)
                                )
                            }
                        }
                    } else {
                        // Vue classique (LazyColumn/Grid)
                        if (state.isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(120.dp),
                                modifier = Modifier.fillMaxSize().verticalGridScrollbar(gridState),
                                state = gridState,
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.files) { item ->
                                    FileItemCard(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        isTrash = isTrashView,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleSelection(item.path)
                                            } else if (isTrashView) {
                                                itemToDelete = item
                                            } else {
                                                if (item.isDirectory) viewModel.open(item)
                                                else openItem(context, item, onOpenImage, onOpenVideo, onOpenAudio, onOpenText)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(item.path) },
                                        onFavorite = { viewModel.toggleFavorite(item) },
                                        onCopy = { viewModel.setClipboard(item, isMove = false) },
                                        onMove = { viewModel.setClipboard(item, isMove = true) },
                                        onRename = { itemToRename = item },
                                        onDelete = { itemToDelete = item },
                                        onShare = { ShareUtils.shareFile(context, File(item.path), item.mimeType) },
                                        onCompress = { itemToCompress = item },
                                        onExtract = { viewModel.setPendingExtraction(item) },
                                        onRestore = { viewModel.restoreFromTrash(item) },
                                        onEncrypt = { itemToEncrypt = item },
                                        onDecrypt = { itemToDecrypt = item },
                                        onTransfer = { onOpenTransfer(item.path) },
                                        thumbnailSize = state.thumbnailSize
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                                state = listState
                            ) {
                                items(state.files) { item ->
                                    FileItemCard(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        isTrash = isTrashView,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleSelection(item.path)
                                            } else if (isTrashView) {
                                                itemToDelete = item
                                            } else {
                                                if (item.isDirectory) viewModel.open(item)
                                                else openItem(context, item, onOpenImage, onOpenVideo, onOpenAudio, onOpenText)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(item.path) },
                                        onFavorite = { viewModel.toggleFavorite(item) },
                                        onCopy = { viewModel.setClipboard(item, isMove = false) },
                                        onMove = { viewModel.setClipboard(item, isMove = true) },
                                        onRename = { itemToRename = item },
                                        onDelete = { itemToDelete = item },
                                        onShare = { ShareUtils.shareFile(context, File(item.path), item.mimeType) },
                                        onCompress = { itemToCompress = item },
                                        onExtract = { viewModel.setPendingExtraction(item) },
                                        onRestore = { viewModel.restoreFromTrash(item) },
                                        onEncrypt = { itemToEncrypt = item },
                                        onDecrypt = { itemToDecrypt = item },
                                        onTransfer = { onOpenTransfer(item.path) },
                                        thumbnailSize = state.thumbnailSize
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogues
    itemToEncrypt?.let { item ->
        EncryptionDialog(
            item = item,
            isDecrypt = false,
            onDismiss = { itemToEncrypt = null },
            onConfirm = { password ->
                viewModel.encryptFile(item, password)
                itemToEncrypt = null
            }
        )
    }

    itemToDecrypt?.let { item ->
        EncryptionDialog(
            item = item,
            isDecrypt = true,
            onDismiss = { itemToDecrypt = null },
            onConfirm = { password ->
                viewModel.decryptFile(item, password)
                itemToDecrypt = null
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            onDismiss = { showCreateFileDialog = false },
            onConfirm = { name ->
                viewModel.createFile(name)
                showCreateFileDialog = false
            }
        )
    }

    itemToCompress?.let { item ->
        CompressDialog(
            item = item,
            onDismiss = { itemToCompress = null },
            onConfirm = { zipName ->
                viewModel.compress(item, zipName)
                itemToCompress = null
                scope.launch { snackbarHostState.showSnackbar("Compression lancée…") }
            }
        )
    }

    itemToRename?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { itemToRename = null },
            onConfirm = { newName ->
                viewModel.rename(item, newName)
                itemToRename = null
            }
        )
    }

    if (showMultiDeleteDialog) {
        MultiDeleteDialog(
            count = state.selectedPaths.size,
            isTrashView = isTrashView,
            onDismiss = { showMultiDeleteDialog = false },
            onConfirmPermanent = {
                viewModel.deleteSelected()
                showMultiDeleteDialog = false
                scope.launch { snackbarHostState.showSnackbar("Éléments supprimés définitivement") }
            },
            onMoveToTrash = {
                // Je vais ajouter moveToTrashSelected dans le ViewModel
                viewModel.moveToTrashSelected()
                showMultiDeleteDialog = false
                scope.launch { snackbarHostState.showSnackbar("Déplacés vers la corbeille") }
            }
        )
    }

    if (showMultiRenameDialog) {
        MultiRenameDialog(
            count = state.selectedPaths.size,
            onDismiss = { showMultiRenameDialog = false },
            onConfirm = { prefix, suffix, find, replace, counter ->
                viewModel.multiRename(prefix, suffix, find, replace, counter)
                showMultiRenameDialog = false
            }
        )
    }

    itemToDelete?.let { item ->
        DeleteDialog(
            item = item,
            isTrashView = isTrashView,
            onDismiss = { itemToDelete = null },
            onConfirmPermanent = {
                viewModel.delete(item)
                itemToDelete = null
                scope.launch { snackbarHostState.showSnackbar("Fichier supprimé définitivement") }
            },
            onMoveToTrash = {
                if (isTrashView) {
                    viewModel.restoreFromTrash(item)
                    scope.launch { snackbarHostState.showSnackbar("Fichier restauré") }
                } else {
                    viewModel.moveToTrash(item)
                    scope.launch { snackbarHostState.showSnackbar("Déplacé vers la corbeille") }
                }
                itemToDelete = null
            }
        )
    }
}

@Composable
fun SortMenuItem(
    text: String,
    isSelected: Boolean,
    isAscending: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text)
                if (isSelected) {
                    Icon(
                        imageVector = if (isAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick
    )
}

private fun openItem(
    context: Context,
    item: FileItem,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenAudio: (String) -> Unit,
    onOpenText: (String) -> Unit
) {
    when {
        item.name.lowercase().endsWith(".apk") -> installApk(context, item)
        item.mimeType?.startsWith("image/") == true -> onOpenImage(item.path)
        item.mimeType?.startsWith("video/") == true -> onOpenVideo(item.path)
        item.mimeType?.startsWith("audio/") == true -> onOpenAudio(item.path)
        item.mimeType?.startsWith("text/") == true -> onOpenText(item.path)
        else -> openExternalFile(context, item)
    }
}

private fun installApk(context: Context, item: FileItem) {
    val file = File(item.path)
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openExternalFile(context: Context, item: FileItem) {
    val file = File(item.path)
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
