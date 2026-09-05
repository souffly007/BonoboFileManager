package fr.bonobo.filemanager.presentation.ui.cleaner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(
    viewModel: CleanerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Nettoyeur") },
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
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isScanning,
            onRefresh = viewModel::startScan,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.cleanCompleted) {
                    CleanSuccessView(state.freedSpace, onNavigateBack)
                } else if (state.isScanning) {
                    ScanningView()
                } else if (state.scanCompleted) {
                    JunkListView(state, viewModel)
                } else {
                    InitialView(viewModel::startScan)
                }
            }
        }
    }
}

@Composable
fun InitialView(onStartScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CleaningServices,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Prêt à faire le ménage ?",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Scannez votre stockage pour trouver les fichiers inutiles.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Button(
            onClick = onStartScan,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Lancer le scan")
        }
    }
}

@Composable
fun ScanningView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Analyse en cours…", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun JunkListView(state: CleanerUiState, viewModel: CleanerViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Espace libérable", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = FileUtils.formatSize(state.totalJunkSize),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = "Fichiers temporaires (${state.junkFiles.size})",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(state.junkFiles) { file ->
                JunkItemRow(
                    name = file.name,
                    size = FileUtils.formatSize(file.size),
                    isSelected = state.selectedJunkPaths.contains(file.path),
                    onToggle = { viewModel.toggleJunkSelection(file.path) },
                    icon = Icons.Default.Delete
                )
            }

            item {
                Text(
                    text = "Dossiers vides (${state.emptyFolders.size})",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(state.emptyFolders) { folder ->
                JunkItemRow(
                    name = folder.name,
                    size = "Vide",
                    isSelected = state.selectedFolderPaths.contains(folder.path),
                    onToggle = { viewModel.toggleFolderSelection(folder.path) },
                    icon = Icons.Default.Folder
                )
            }
        }

        Button(
            onClick = viewModel::clean,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            enabled = !state.isCleaning && (state.selectedJunkPaths.isNotEmpty() || state.selectedFolderPaths.isNotEmpty())
        ) {
            if (state.isCleaning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text("Nettoyer maintenant")
            }
        }
    }
}

@Composable
fun JunkItemRow(
    name: String,
    size: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(text = size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    }
}

@Composable
fun CleanSuccessView(freedSpace: Long, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CleaningServices,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = Color(0xFF4CAF50)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Ménage terminé !", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Vous avez libéré ${FileUtils.formatSize(freedSpace)} d'espace.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onDone, modifier = Modifier.padding(top = 32.dp)) {
            Text("Super !")
        }
    }
}
