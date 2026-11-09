package fr.bonobo.filemanager.presentation.ui.dashboard

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class LicenseEntry(
    val title: String,
    val fileName: String,
    val description: String
)

private val licenseEntries = listOf(
    LicenseEntry(
        "Mentions des logiciels tiers",
        "THIRD_PARTY_NOTICES.txt",
        "Liste des composants et informations générales"
    ),
    LicenseEntry(
        "Apache License 2.0",
        "APACHE-2.0.txt",
        "AndroidX, Kotlin et composants Apache"
    ),
    LicenseEntry(
        "Bouncy Castle",
        "BOUNCY-CASTLE.txt",
        "Composants cryptographiques"
    ),
    LicenseEntry(
        "SLF4J",
        "SLF4J.txt",
        "Bibliothèque de journalisation"
    ),
    LicenseEntry(
        "Shizuku API",
        "SHIZUKU-MIT.txt",
        "Licence MIT"
    ),
    LicenseEntry(
        "MBassador",
        "MBASSADOR-MIT.txt",
        "Licence MIT"
    ),
    LicenseEntry(
        "EdDSA for Java",
        "EDDSA-CC0.txt",
        "CC0 1.0 Universal"
    ),
    LicenseEntry(
        "UnRAR / Junrar",
        "UNRAR.txt",
        "Licence applicable au support des archives RAR"
    ),
    LicenseEntry(
        "XZ for Java",
        "XZ-JAVA.txt",
        "Licence du composant XZ"
    ),
    LicenseEntry(
        "SSHJ — Notice",
        "SSHJ-NOTICE.txt",
        "Attributions et mentions complémentaires"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedLicense by remember { mutableStateOf<LicenseEntry?>(null) }

    if (selectedLicense != null) {
        LicenseTextScreen(
            entry = selectedLicense!!,
            context = context,
            onNavigateBack = {
                selectedLicense = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licences des logiciels tiers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Bonobo Files Explorateur utilise des composants " +
                        "développés par des tiers. Chaque composant reste soumis " +
                        "à sa propre licence.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = 4.dp,
                    end = 4.dp,
                    bottom = 16.dp
                )
            )

            licenseEntries.forEachIndexed { index, entry ->

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedLicense = entry
                        }
                        .padding(
                            horizontal = 4.dp,
                            vertical = 14.dp
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall
                        )

                        Spacer(
                            modifier = Modifier.height(4.dp)
                        )

                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                if (index < licenseEntries.lastIndex) {
                    HorizontalDivider()
                }
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseTextScreen(
    entry: LicenseEntry,
    context: Context,
    onNavigateBack: () -> Unit
) {
    val text = remember(entry.fileName) {
        loadLicenseText(
            context = context,
            fileName = entry.fileName
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(entry.title)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun loadLicenseText(
    context: Context,
    fileName: String
): String {
    return try {
        context.assets
            .open("licenses/$fileName")
            .bufferedReader()
            .use { it.readText() }
    } catch (exception: Exception) {
        "Impossible de charger le fichier de licence : $fileName"
    }
}