package fr.bonobo.filemanager.presentation.ui.apps

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.domain.model.AppItem
import fr.bonobo.filemanager.presentation.components.SearchBar
import fr.bonobo.filemanager.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(
    viewModel: AppViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onBackupTo: (AppItem) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Gestion des Apps") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Accueil")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::search
                )

                if (state.isLoading && state.apps.isEmpty()) {
                    Text(text = "Chargement des applications…", modifier = Modifier.padding(16.dp))
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.filteredApps) { app ->
                        AppItemCard(
                            app = app,
                            onLaunch = {
                                val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                if (intent != null) context.startActivity(intent)
                            },
                            onBackup = { viewModel.backupApp(app) },
                            onBackupTo = { onBackupTo(app) },
                            onUninstall = {
                                val intent = Intent(Intent.ACTION_DELETE).apply {
                                    data = Uri.parse("package:${app.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            onSettings = {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${app.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemCard(
    app: AppItem,
    onLaunch: () -> Unit,
    onBackup: () -> Unit,
    onBackupTo: () -> Unit,
    onUninstall: () -> Unit,
    onSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            app.icon?.let {
                Image(
                    bitmap = it.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(text = app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = "${app.versionName} • ${FileUtils.formatSize(app.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Lancer") },
                    leadingIcon = { Icon(Icons.Default.Launch, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onLaunch()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Sauvegarde rapide") },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onBackup()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Sauvegarder vers…") },
                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onBackupTo()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Infos système") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onSettings()
                    }
                )
                if (!app.isSystemApp) {
                    DropdownMenuItem(
                        text = { Text("Désinstaller") },
                        onClick = {
                            menuExpanded = false
                            onUninstall()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }
        }
    }
}
