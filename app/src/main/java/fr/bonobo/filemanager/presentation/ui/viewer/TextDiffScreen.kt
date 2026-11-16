package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextDiffScreen(
    file1Path: String,
    file2Path: String,
    onNavigateBack: () -> Unit
) {
    val file1 = remember { File(file1Path) }
    val file2 = remember { File(file2Path) }
    
    var lines1 by remember { mutableStateOf(emptyList<String>()) }
    var lines2 by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(file1Path, file2Path) {
        withContext(Dispatchers.IO) {
            lines1 = if (file1.exists()) file1.readLines() else emptyList()
            lines2 = if (file2.exists()) file2.readLines() else emptyList()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Comparaison") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            DiffPanel(modifier = Modifier.weight(1f), title = file1.name, lines = lines1, otherLines = lines2)
            VerticalDivider()
            DiffPanel(modifier = Modifier.weight(1f), title = file2.name, lines = lines2, otherLines = lines1)
        }
    }
}

@Composable
fun DiffPanel(modifier: Modifier, title: String, lines: List<String>, otherLines: List<String>) {
    Column(modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                val isDifferent = index >= otherLines.size || line != otherLines[index]
                Text(
                    text = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDifferent) Color.Red.copy(alpha = 0.1f) else Color.Transparent)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}
