package fr.bonobo.filemanager.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.components.IconTile
import fr.bonobo.filemanager.presentation.components.ThumbnailPreset
import fr.bonobo.filemanager.presentation.ui.theme.AppThemeMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), onNavigateBack: () -> Unit = {}) {
    val hidden by viewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val root by viewModel.rootAccess.collectAsStateWithLifecycle()
    val thumbnailSize by viewModel.thumbnailSize.collectAsStateWithLifecycle()
    var slider by remember(thumbnailSize) { mutableFloatStateOf(ThumbnailPreset.fromKey(thumbnailSize).ordinal.toFloat()) }
    val preset = ThumbnailPreset.entries[slider.roundToInt().coerceIn(0, 5)]

    Scaffold(topBar = {
        TopAppBar(title = { Text("Paramètres") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            DiagnosticSettings()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Miniatures", style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Taille des aperçus", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(preset.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = slider, onValueChange = { slider = it }, valueRange = 0f..5f, steps = 4,
                            onValueChangeFinished = { viewModel.setThumbnailSize(preset.name) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Compact", style = MaterialTheme.typography.labelMedium)
                            Text("XXL", style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Outlined.Image, size = minOf(preset.listSize, 112).dp, tone = 2)
                            Column(Modifier.weight(1f)) {
                                Text("Aperçu photo", style = MaterialTheme.typography.bodyMedium)
                                Text("Le réglage s'applique aux photos et vidéos.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("Pour voir les images en grand, utilisez la vue grille dans l'explorateur. Les icônes de dossiers restent compactes.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Apparence", style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    listOf(AppThemeMode.SYSTEM to "Automatique", AppThemeMode.LIGHT to "Clair",
                        AppThemeMode.DARK to "Sombre", AppThemeMode.CYANOGEN to "CyanogenMOD").forEach { (mode, label) ->
                        val icon = when (mode) {
                            AppThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness
                            AppThemeMode.LIGHT -> Icons.Outlined.LightMode
                            AppThemeMode.DARK -> Icons.Outlined.DarkMode
                            AppThemeMode.CYANOGEN -> Icons.Outlined.Palette
                        }
                        Row(Modifier.fillMaxWidth().selectable(theme == mode, role = Role.RadioButton,
                            onClick = { viewModel.setThemeMode(mode) }).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile(icon, size = 38.dp)
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            RadioButton(selected = theme == mode, onClick = null)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Exploration", style = MaterialTheme.typography.titleMedium)
                SettingsToggle("Fichiers cachés", "Afficher les noms commençant par un point", hidden, viewModel::setShowHiddenFiles)
                SettingsToggle("Accès système", "Un appareil rooté est nécessaire", root, viewModel::setRootAccess)
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun DiagnosticSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val journal = fr.bonobo.filemanager.diagnostic.Diagnostic.journal
    var enabled by remember { mutableStateOf(journal?.enabled == true) }
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val export = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use {
                    journal?.export(it) == true
                } == true }.getOrDefault(false)
            }
            status = if (success) "Journal exporté" else "Échec de l’export"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Diagnostic technique", style = MaterialTheme.typography.titleMedium)
        SettingsToggle("Activer le journal", "Local et privé, limité à 1 Mo. Désactivé à chaque démarrage. Aucun nom, URI ou contenu enregistré.", enabled) {
            journal?.setEnabled(it); enabled = it
        }
        Button(onClick = { export.launch("bonobo-diagnostic.txt") }) { Text("Exporter le journal") }
        OutlinedButton(onClick = { confirm = true }) { Text("Effacer le journal") }
        if (status.isNotEmpty()) Text(status)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Effacer le journal ?") },
        text = { Text("Cette action supprime les traces techniques locales.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { journal?.clear() == true }
                status = if (success) "Journal effacé" else "Échec de l’effacement"
            }
        }) { Text("Effacer") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } })
}
