package fr.bonobo.filemanager.presentation.ui.operations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.transfer.ConflictAction

/**
 * Destination conflict: Remplacer / Renommer / Ignorer / Fusionner (folders), with « Appliquer à tous ».
 * @param sameFolder copy into the source folder itself: only Renommer / Ignorer make sense.
 */
@Composable
fun ConflictDialog(
    item: FileItem,
    remaining: Int,
    isMove: Boolean,
    sameFolder: Boolean,
    existing: FileItem? = null,
    /** null: not compared (yet); true: same size and same SHA-256. */
    identical: Boolean? = null,
    onChoice: (action: ConflictAction, applyToAll: Boolean, compareHash: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var applyToAll by remember(item.path) { mutableStateOf(false) }
    var compareHash by remember(item.path) { mutableStateOf(false) }
    val kind = if (item.isDirectory) "Un dossier" else "Un fichier"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("« ${item.name} » existe déjà") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$kind portant ce nom se trouve déjà dans la destination. Que faire ?")
                Versions(item, existing, identical)
                if (item.isDirectory && !sameFolder) Choice("Fusionner",
                    "Ajoute seulement ce qui manque. Les fichiers déjà présents avec la même taille sont ignorés : " +
                        "idéal pour reprendre une copie annulée.") { onChoice(ConflictAction.MERGE, applyToAll, compareHash) }
                if (!sameFolder) Choice("Remplacer",
                    if (item.isDirectory) "Fusionne et remplace les fichiers différents. La nouvelle version est vérifiée avant de remplacer l’ancienne ; les autres fichiers du dossier sont conservés."
                    else "La nouvelle version est entièrement copiée et vérifiée avant de remplacer l’ancienne.") {
                    onChoice(ConflictAction.REPLACE, applyToAll, compareHash)
                }
                Choice("Renommer", "${if (isMove) "Déplace" else "Copie"} sous « ${suggestion(item)} ».") {
                    onChoice(ConflictAction.RENAME, applyToAll, compareHash)
                }
                Choice("Ignorer", "Ne rien faire pour cet élément.") { onChoice(ConflictAction.SKIP, applyToAll, compareHash) }
                if (item.isDirectory && !sameFolder) Row(Modifier.fillMaxWidth().clickable { compareHash = !compareHash },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = compareHash, onCheckedChange = { compareHash = it })
                    Text("Fusionner : comparer aussi le contenu (SHA-256, plus lent)", style = MaterialTheme.typography.bodySmall)
                }
                if (remaining > 1) Row(Modifier.fillMaxWidth().clickable { applyToAll = !applyToAll },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Appliquer à tous (${remaining} conflits)")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Annuler ${if (isMove) "le déplacement" else "la copie"}") } }
    )
}

/** Size and date of both versions; « Fichiers identiques » when size and SHA-256 match. */
@Composable
private fun Versions(item: FileItem, existing: FileItem?, identical: Boolean?) {
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    fun describe(file: FileItem) = listOfNotNull(
        if (file.isDirectory) null else fr.bonobo.filemanager.util.FileUtils.formatSize(file.size),
        file.lastModified.time.takeIf { it > 0 }?.let { format.format(file.lastModified) }
    ).joinToString(" · ").ifEmpty { "taille et date inconnues" }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Nouvelle version : ${describe(item)}", style = MaterialTheme.typography.bodySmall)
        if (existing != null) Text("Déjà présent : ${describe(existing)}", style = MaterialTheme.typography.bodySmall)
        if (existing != null && !item.isDirectory && !existing.isDirectory && existing.size == item.size) {
            Text(when (identical) {
                true -> "Fichiers identiques (même taille, même contenu SHA-256) : « Ignorer » suffit."
                false -> "Même taille, mais contenu différent."
                null -> "Même taille : comparaison du contenu en cours…"
            }, style = MaterialTheme.typography.bodySmall,
                color = if (identical == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Choice(label: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun suggestion(item: FileItem): String {
    val dot = if (item.isDirectory) -1 else item.name.lastIndexOf('.')
    return if (dot > 0) "${item.name.substring(0, dot)} (1)${item.name.substring(dot)}" else "${item.name} (1)"
}
