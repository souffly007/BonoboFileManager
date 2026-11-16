package fr.bonobo.filemanager.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.domain.transfer.TransferText.plural
import fr.bonobo.filemanager.util.FileUtils

/**
 * @param retry null when the last request is no longer known (e.g. after a restart) or nothing can be retried.
 */
@Composable
fun TransferPanel(state: TransferState, cancel: () -> Unit, dismiss: () -> Unit, retry: (() -> Unit)? = null,
    retryBlocked: String? = null) {
    if (state.id.isEmpty()) return
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    var details by remember(state.id) { mutableStateOf(false) }
    if (state.acknowledged && !details && !state.active) return
    val failed = state.phase in listOf(TransferPhase.ERROR, TransferPhase.INTERRUPTED)
    if (!state.acknowledged || state.active) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = if (failed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val heading = title(state)
            Text(heading, style = MaterialTheme.typography.titleSmall,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            if (state.active && state.currentName.isNotEmpty()) Text(state.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.active && !compact && state.destination.isNotEmpty())
                Text("Vers : ${displayPath(state.destination)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (state.active) {
                val fraction = state.copyFraction
                val verified = verifyFraction(state)
                if (state.phase == TransferPhase.COPYING && fraction != null)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else if (verified != null) {
                    LinearProgressIndicator(progress = { verified }, modifier = Modifier.fillMaxWidth())
                    Text("Vérification : ${(verified * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                }
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (!compact) {
                    Text(plural(state.completedFiles, "fichier terminé", "fichiers terminés") +
                        (state.totalFiles?.let { " sur $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (state.phase != TransferPhase.PREPARING) {
                        Text(FileUtils.formatSize(state.bytes) + (state.totalBytes?.let { " / ${FileUtils.formatSize(it)}" } ?: "") +
                            (state.copyFraction?.takeIf { state.phase == TransferPhase.COPYING }?.let { " · ${(it * 100).toInt()} %" } ?: "") +
                            (state.speed?.takeIf { state.phase == TransferPhase.COPYING }?.let { " · ${FileUtils.formatSize(it)}/s" } ?: "") +
                            (state.etaSeconds?.takeIf { state.phase == TransferPhase.COPYING }?.let { " · ${duration(it)} restant" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                withoutTitle(state.notice, heading)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
            if (state.cancelRequested && state.active) Text("Annulation demandée ; suppression des fichiers partiels…", style = MaterialTheme.typography.bodySmall)
            if (!state.active && retry != null && retryBlocked != null && state.failure != FailureKind.CONFLICT)
                Text(retryBlocked, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.active && retry != null && state.failure != FailureKind.CONFLICT)
                    Button(onClick = retry, enabled = retryBlocked == null) { Text("Réessayer") }
                TextButton(onClick = { details = true; if (!state.active) dismiss() }) { Text("Détails") }
                if (state.active) TextButton(onClick = cancel, enabled = !state.cancelRequested) { Text("Annuler") }
                else TextButton(onClick = dismiss) { Text("Fermer") }
            }
        }
    }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(title(state)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    // The notice is shown once; item messages below never repeat it.
                    withoutTitle(state.notice, title(state))?.let { Text(it) }
                    if (state.active && state.currentName.isNotEmpty()) Text(state.currentName)
                    if (state.destination.isNotEmpty()) Text("Destination : ${displayPath(state.destination)}", style = MaterialTheme.typography.bodySmall)
                    verifyFraction(state)?.takeIf { state.active }?.let { Text("Vérification : ${(it * 100).toInt()} %") }
                    if (state.active && state.phase == TransferPhase.COPYING)
                        Text("Fichier courant : ${FileUtils.formatSize(state.fileBytes)}" + (state.fileTotal?.let { " / ${FileUtils.formatSize(it)}" } ?: ""))
                    Text(plural(state.completedFiles, "fichier terminé et vérifié", "fichiers terminés et vérifiés") +
                        (state.totalFiles?.let { " sur $it" } ?: ""))
                    Text("${state.processedRoots}/${state.totalRoots} " + if (state.totalRoots > 1) "éléments traités" else "élément traité")
                    Text("Copié : ${FileUtils.formatSize(state.bytes)}" + (state.totalBytes?.let { " / ${FileUtils.formatSize(it)}" } ?: ""))
                }
                items(state.results) { result ->
                    HorizontalDivider()
                    Text(result.source.name, style = MaterialTheme.typography.titleSmall)
                    // « Non démarré : espace insuffisant » is the status itself, not « Annulé ».
                    val notStarted = result.status == ItemStatus.NOT_STARTED
                    val status = if (notStarted) result.message?.substringBefore(". ")?.removeSuffix(".") ?: "Non démarré"
                    else when (result.status) {
                        ItemStatus.DONE -> "Terminé et vérifié"
                        ItemStatus.FAILED -> "Erreur"
                        ItemStatus.CANCELLED -> "Annulé"
                        ItemStatus.UNCERTAIN -> "Interrompu"
                        ItemStatus.SKIPPED -> "Ignoré"
                        ItemStatus.NOT_STARTED -> "Non démarré"
                    }
                    Text(status, color = if (result.status == ItemStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    val message = if (notStarted) result.message?.substringAfter(". ", "")?.ifBlank { null } else result.message
                    withoutTitle(message?.takeIf { it != state.notice }, title(state))?.takeIf { it != status }
                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                val technical = state.results.mapNotNull { r -> r.detail?.let { "${r.source.name} : $it" } }
                if (technical.isNotEmpty() || !state.active) item {
                    HorizontalDivider()
                    Text("Informations techniques", style = MaterialTheme.typography.labelMedium)
                    technical.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Chaque fichier est vérifié (SHA-256) juste après sa copie, puis renommé : un fichier interrompu ne porte jamais son nom final. " +
                        "La lecture après éjection et rebranchement de la clé n’est pas contrôlée.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Fermer les détails") } })
}

private fun verb(state: TransferState) = when (state.operation) {
    TransferOperation.MOVE -> "Déplacement"
    TransferOperation.DUPLICATE -> "Duplication"
    TransferOperation.COPY -> "Copie"
    TransferOperation.TRASH -> "Mise à la corbeille"
    TransferOperation.RESTORE -> "Restauration"
}
private fun agree(state: TransferState) = if (state.operation == TransferOperation.MOVE) "" else "e"

/** Precise card title according to the cause. */
private fun title(state: TransferState): String {
    val action = verb(state)
    return when {
        state.phase == TransferPhase.ERROR && state.failure == FailureKind.USB_REMOVED -> "Clé USB retirée pendant la ${action.lowercase()}"
        state.phase == TransferPhase.ERROR && state.failure == FailureKind.DISK_FULL -> if (state.failureOnUsb) "Clé USB pleine" else "Stockage plein"
        state.phase == TransferPhase.ERROR && state.failure == FailureKind.INSUFFICIENT_SPACE -> if (state.failureOnUsb) "Espace insuffisant sur la clé USB" else "Espace insuffisant"
        state.phase == TransferPhase.ERROR && state.failure == FailureKind.FILE_TOO_LARGE -> "Fichier trop volumineux pour ce support"
        state.phase == TransferPhase.ERROR && state.failure == FailureKind.CONFLICT -> "Un élément existe déjà à la destination"
        state.phase == TransferPhase.ERROR -> "$action terminé${agree(state)} avec ${plural(state.results.count { it.status == ItemStatus.FAILED || it.status == ItemStatus.UNCERTAIN }.coerceAtLeast(1), "erreur")}"
        state.phase == TransferPhase.CANCELLED -> "$action annulé${agree(state)}"
        state.phase == TransferPhase.ERROR && state.results.isNotEmpty() && state.results.all { it.status == ItemStatus.NOT_STARTED } ->
            "$action non démarré${agree(state)}"
        state.phase == TransferPhase.INTERRUPTED -> "$action interrompu${agree(state)}"
        state.phase == TransferPhase.DONE -> "$action terminé${agree(state)} et vérifié${agree(state)}"
        state.phase == TransferPhase.PREPARING -> "Préparation"
        state.phase == TransferPhase.COPYING -> "$action en cours"
        state.phase == TransferPhase.VERIFYING -> "Vérification"
        state.phase == TransferPhase.PUBLISHING -> "Finalisation"
        state.phase == TransferPhase.DELETING -> "Copie vérifiée · suppression de la source"
        state.phase == TransferPhase.CANCEL_REQUESTED -> "Annulation en cours"
        else -> action
    }
}

/** SHA-256 verification progress of the current file (null = unknown → indeterminate bar). */
private fun verifyFraction(state: TransferState): Float? {
    if (state.phase != TransferPhase.VERIFYING) return null
    val total = state.fileTotal?.takeIf { it > 0 } ?: return null
    return (state.fileBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Never repeat the card title in its text: « Clé USB pleine : 1,5 Go copiés… » under the title
 * « Clé USB pleine » becomes « 1,5 Go copiés… ». A text equal to the title is not shown.
 */
internal fun withoutTitle(text: String?, title: String): String? {
    if (text.isNullOrBlank()) return null
    val trimmed = text.trim()
    if (trimmed.removeSuffix(".").equals(title, ignoreCase = true)) return null
    if (!trimmed.startsWith(title, ignoreCase = true)) return trimmed
    val rest = trimmed.substring(title.length)
    val separator = Regex("^\\s*([:.·;,]|—)\\s*").find(rest) ?: return trimmed
    return rest.substring(separator.range.last + 1).replaceFirstChar { it.uppercase() }.ifBlank { null }
}

private fun duration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

/** Readable destination instead of a raw content:// URI. */
private fun displayPath(path: String): String {
    if (path.trimEnd('/').endsWith("/.bonobo_trash")) return "Corbeille"
    if (!path.startsWith("content://")) return path
    return runCatching {
        val id = android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(path))
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        (if (volume.equals("primary", true)) "Stockage interne" else "Clé USB ($volume)") + if (relative.isNotEmpty()) " › ${relative.replace("/", " › ")}" else ""
    }.getOrDefault(path)
}
