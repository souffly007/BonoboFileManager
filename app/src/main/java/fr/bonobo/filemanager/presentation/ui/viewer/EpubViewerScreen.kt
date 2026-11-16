package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewerScreen(path: String, onNavigateBack: () -> Unit) {
    var content by remember { mutableStateOf("Chargement…") }
    LaunchedEffect(path) {
        content = withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(File(path)).use { zip ->
                    zip.entries.asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                (entry.name.endsWith(".xhtml", true) ||
                                    entry.name.endsWith(".html", true) ||
                                    entry.name.endsWith(".htm", true))
                        }
                        .joinToString("\n\n") { entry ->
                            zip.getInputStream(entry).bufferedReader().use { reader ->
                                reader.readText()
                                    .replace(Regex("<[^>]*>"), " ")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                            }
                        }
                        .ifBlank { "EPUB sans texte lisible" }
                }
            }.getOrElse { error ->
                "Impossible de lire cet EPUB : ${error.message}"
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(File(path).name) }, navigationIcon = { IconButton(onNavigateBack) { Icon(Icons.Default.ArrowBack, "Retour") } }) }) { padding ->
        Text(content, Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()))
    }
}
