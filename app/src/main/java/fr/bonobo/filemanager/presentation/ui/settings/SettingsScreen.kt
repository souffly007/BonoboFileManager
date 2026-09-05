package fr.bonobo.filemanager.presentation.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.bonobo.filemanager.presentation.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val showHiddenFiles by viewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val rootAccess by viewModel.rootAccess.collectAsStateWithLifecycle()
    val thumbnailSize by viewModel.thumbnailSize.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Paramètres") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Affichage",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Afficher les fichiers cachés",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Inclure les fichiers commençant par un point",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = showHiddenFiles,
                        onCheckedChange = viewModel::setShowHiddenFiles
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accès Root / Système",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Accès aux dossiers système (Root requis)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = rootAccess,
                        onCheckedChange = viewModel::setRootAccess
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Taille des miniatures",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("SMALL" to "Petites", "MEDIUM" to "Moyennes", "LARGE" to "Grandes").forEach { (valStr, label) ->
                        FilterChip(
                            selected = thumbnailSize == valStr,
                            onClick = { viewModel.setThumbnailSize(valStr) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Thème de l'application",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ThemeOption(
                        title = "Système (Défaut)",
                        selected = themeMode == AppThemeMode.SYSTEM,
                        onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    ThemeOption(
                        title = "Clair",
                        selected = themeMode == AppThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    ThemeOption(
                        title = "Sombre",
                        selected = themeMode == AppThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(AppThemeMode.DARK) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    ThemeOption(
                        title = "CyanogenMOD",
                        selected = themeMode == AppThemeMode.CYANOGEN,
                        onClick = { viewModel.setThemeMode(AppThemeMode.CYANOGEN) }
                    )
                }
            }
        }
    }
}

@Composable
fun ThemeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = onClick)
    }
}
