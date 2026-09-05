package fr.bonobo.filemanager.presentation.ui.dashboard

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.components.StorageBar
import fr.bonobo.filemanager.presentation.ui.main.MainViewModel
import fr.bonobo.filemanager.presentation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel = hiltViewModel(),
    settingsViewModel: fr.bonobo.filemanager.presentation.ui.settings.SettingsViewModel = hiltViewModel(),
    onNavigateToExplorer: () -> Unit,
    onNavigateToCategory: (String) -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToCleaner: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rootAccess by settingsViewModel.rootAccess.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val vaultPassword by settingsViewModel.vaultPassword.collectAsStateWithLifecycle()

    var showVaultAccess by remember { mutableStateOf(false) }

    if (showVaultAccess) {
        fr.bonobo.filemanager.presentation.ui.operations.VaultAccessDialog(
            storedPassword = vaultPassword,
            onDismiss = { showVaultAccess = false },
            onSetPassword = { settingsViewModel.setVaultPassword(it) },
            onSuccess = {
                showVaultAccess = false
                onNavigateToCategory("Coffre-fort")
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(text = "Bonobo Explorateur", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🦍",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Quitter")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Default.Info, contentDescription = "À propos")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            state.storageInfo?.let {
                StorageBar(storageInfo = it)
            }

            Text(
                text = "Catégories",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            val categories = mutableListOf(
                CategoryItem("Explorateur", Icons.Default.Folder, CategoryFolder),
                CategoryItem("Téléchargements", Icons.Default.Download, CategoryDownload),
                CategoryItem("Gros fichiers", Icons.Default.Storage, CategoryLargeFiles),
                CategoryItem("Images", Icons.Default.Image, CategoryImages),
                CategoryItem("Vidéos", Icons.Default.VideoFile, CategoryVideos),
                CategoryItem("Musique", Icons.Default.AudioFile, CategoryMusic),
                CategoryItem("Documents", Icons.Default.Description, CategoryDocs),
                CategoryItem("Corbeille", Icons.Default.Delete, CategoryTrash),
                CategoryItem("Coffre-fort", Icons.Default.Lock, CategoryVault),
                CategoryItem("Réseau", Icons.Default.Lan, CategoryFolder),
                CategoryItem("Applications", Icons.Default.Apps, CategoryDocs)
            )

            if (rootAccess) {
                categories.add(CategoryItem("Système Root", Icons.Default.Lock, CategoryLargeFiles))
            }

            // Ajouter les marque-pages
            bookmarks.forEach { bookmark ->
                categories.add(CategoryItem(bookmark.name, Icons.Default.Bookmark, Color(0xFFFFEB3B)))
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(categories) { category ->
                    var showDeleteBookmark by remember { mutableStateOf(false) }
                    var showShortcutMenu by remember { mutableStateOf(false) }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    
                    Box {
                        if (showShortcutMenu) {
                            DropdownMenu(
                                expanded = showShortcutMenu,
                                onDismissRequest = { showShortcutMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Créer un raccourci bureau") },
                                    onClick = {
                                        showShortcutMenu = false
                                        fr.bonobo.filemanager.util.ShortcutUtils.createCategoryShortcut(
                                            context = context,
                                            id = category.title,
                                            label = category.title
                                        )
                                    }
                                )
                            }
                        }
                        
                        if (showDeleteBookmark) {
                            AlertDialog(
                                onDismissRequest = { showDeleteBookmark = false },
                                title = { Text("Supprimer le raccourci") },
                                text = { Text("Voulez-vous supprimer le marque-page « ${category.title} » ?") },
                                confirmButton = {
                                    Button(onClick = { 
                                        val b = bookmarks.find { it.name == category.title }
                                        if (b != null) viewModel.removeBookmark(b.path)
                                        showDeleteBookmark = false 
                                    }) {
                                        Text("Supprimer")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteBookmark = false }) {
                                        Text("Annuler")
                                    }
                                }
                            )
                        }

                        CategoryCard(
                            category = category,
                            onClick = {
                                when (category.title) {
                                    "Explorateur" -> onNavigateToExplorer()
                                    "Réseau" -> onNavigateToNetwork()
                                    "Applications" -> onNavigateToApps()
                                    "Nettoyeur" -> onNavigateToCleaner()
                                    "Coffre-fort" -> showVaultAccess = true
                                    "Système Root" -> {
                                        viewModel.loadFiles("/")
                                        onNavigateToExplorer()
                                    }
                                    else -> {
                                        // Vérifier si c'est un marque-page
                                        val bookmark = bookmarks.find { it.name == category.title }
                                        if (bookmark != null) {
                                            viewModel.loadFiles(bookmark.path)
                                            onNavigateToExplorer()
                                        } else {
                                            onNavigateToCategory(category.title)
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                if (bookmarks.any { it.name == category.title }) {
                                    showDeleteBookmark = true
                                } else {
                                    showShortcutMenu = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

data class CategoryItem(
    val title: String,
    val icon: ImageVector,
    val color: Color
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryCard(
    category: CategoryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = category.color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
