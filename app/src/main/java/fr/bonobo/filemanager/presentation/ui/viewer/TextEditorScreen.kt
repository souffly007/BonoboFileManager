package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filePath: String,
    onNavigateBack: () -> Unit,
    onSaveOverride: (suspend (String) -> Result<Unit>)? = null
) {
    val file = remember(filePath) { File(filePath) }
    var text by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var isRegex by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }
    var originalText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(filePath) {
        text = withContext(Dispatchers.IO) {
            if (file.exists()) file.readText() else ""
        }
        originalText = text
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (text != originalText) "${file.name} •" else file.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Rechercher")
                    }
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val result = if (onSaveOverride != null) onSaveOverride(text) else runCatching { file.writeText(text) }
                            withContext(Dispatchers.Main) {
                                if (result.isSuccess) {
                                    originalText = text
                                    savedNotice = true
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Enregistrer")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            if (savedNotice) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1600)
                    savedNotice = false
                }
                Text(
                    "Enregistré",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (showSearch) {
                SearchReplaceBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    replaceQuery = replaceQuery,
                    onReplaceQueryChange = { replaceQuery = it },
                    isRegex = isRegex,
                    onRegexToggle = { isRegex = it },
                    onReplace = {
                        text = if (isRegex) {
                            text.replace(Regex(searchQuery), replaceQuery)
                        } else {
                            text.replace(searchQuery, replaceQuery)
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Numérotation des lignes
                    LineNumbers(text = text)
                    
                    // Éditeur
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
fun LineNumbers(text: String) {
    val lineCount = text.lines().size
    Column(
        modifier = Modifier
            .width(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.End
    ) {
        for (i in 1..lineCount) {
            Text(
                text = "$i",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

@Composable
fun SearchReplaceBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    isRegex: Boolean,
    onRegexToggle: (Boolean) -> Unit,
    onReplace: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Rechercher") },
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Regex",
                            tint = if (isRegex) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(20.dp).padding(4.dp).background(if (isRegex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        )
                    }
                )
                Checkbox(checked = isRegex, onCheckedChange = onRegexToggle)
                Text("Regex", style = MaterialTheme.typography.bodySmall)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = replaceQuery,
                    onValueChange = onReplaceQueryChange,
                    placeholder = { Text("Remplacer par") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onReplace, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Tout")
                }
            }
        }
    }
}
