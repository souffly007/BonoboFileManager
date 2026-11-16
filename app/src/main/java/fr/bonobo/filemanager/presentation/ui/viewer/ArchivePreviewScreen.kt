package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.model.ArchiveEntry
import fr.bonobo.filemanager.domain.usecase.ListArchiveEntriesUseCase
import fr.bonobo.filemanager.util.FileUtils
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePreviewScreen(path: String, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope(); var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { scope.launch { ListArchiveEntriesUseCase()(File(path)).onSuccess { entries = it }.onFailure { error = it.message } } }
    Scaffold(topBar = { TopAppBar(title = { Text("Contenu : ${File(path).name}") }, navigationIcon = { IconButton(onNavigateBack) { Icon(Icons.Default.ArrowBack, "Retour") } }) }) { padding ->
        if (error != null) Text("Archive protégée ou illisible. Utilisez l’extraction avec mot de passe.\n$error", Modifier.padding(padding).padding(16.dp))
        else LazyColumn(Modifier.padding(padding).fillMaxSize()) { items(entries) { e -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) { Text(if (e.isDirectory) "📁" else "📄"); Spacer(Modifier.width(10.dp)); Column { Text(e.name); if (!e.isDirectory && e.size >= 0) Text(FileUtils.formatSize(e.size), style = MaterialTheme.typography.bodySmall) } } } }
    }
}
