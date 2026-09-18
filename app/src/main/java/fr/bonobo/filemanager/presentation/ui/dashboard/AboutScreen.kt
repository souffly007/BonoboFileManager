package fr.bonobo.filemanager.presentation.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.BuildConfig
import fr.bonobo.filemanager.presentation.components.IconTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("À propos") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(Icons.Outlined.FolderCopy, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text("Bonobo Explorateur", style = MaterialTheme.typography.titleLarge)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Vos fichiers, simplement.", style = MaterialTheme.typography.bodyLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AboutDetail("Création", "Franck R.-F. · souffly007", "by DonkeyKong")
                    HorizontalDivider()
                    AboutDetail("Logiciel libre", "GNU GPL v3 ou ultérieure", "SPDX : GPL-3.0-or-later")
                }
            }
            Text("Vous pouvez utiliser, étudier, modifier et redistribuer ce logiciel dans les conditions de sa licence. " +
                "Le texte complet accompagne les sources dans le fichier LICENSE.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Copyright © 2025 Franck R.-F.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutDetail(title: String, value: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
