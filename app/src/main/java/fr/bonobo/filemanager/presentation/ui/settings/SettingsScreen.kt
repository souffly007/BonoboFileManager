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
