package fr.bonobo.filemanager.presentation.ui.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.components.FileItemCard
import fr.bonobo.filemanager.util.verticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteExplorerScreen(
    viewModel: RemoteViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.connection?.name ?: "Explorateur distant")
                        Text(state.currentPath, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (state.currentPath == "/") onNavigateBack else viewModel::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Accueil")
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.error?.let {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                    state = listState
                ) {
                    items(state.files) { item ->
                        var showDeleteDialog by remember { mutableStateOf(false) }

                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("Supprimer l'élément distant") },
                                text = { Text("Voulez-vous supprimer « ${item.name} » ? Cette action est irréversible (pas de corbeille sur les serveurs).") },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel.deleteFile(item)
                                            showDeleteDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Supprimer")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Annuler")
                                    }
                                }
                            )
                        }

                        FileItemCard(
                            item = item,
                            onClick = { viewModel.open(item) },
                            onLongClick = { /* Multi-select not yet on FTP */ },
                            onFavorite = {}, // Not on FTP
                            onCopy = {},
                            onMove = {},
                            onRename = {},
                            onDelete = { showDeleteDialog = true },
                            onShare = {},
                            onCompress = {},
                            onExtract = {},
                            onRestore = {},
                            onEncrypt = {},
                            onDecrypt = {},
                            onTransfer = {}
                        )
                    }
                }
            }
        }
    }
}
