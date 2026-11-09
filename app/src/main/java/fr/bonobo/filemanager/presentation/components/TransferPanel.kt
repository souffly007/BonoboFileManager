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
import fr.bonobo.filemanager.util.FileUtils

@Composable
fun TransferPanel(state: TransferState, cancel: () -> Unit, dismiss: () -> Unit) {
    if (state.id.isEmpty()) return
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    var details by remember(state.id) { mutableStateOf(false) }
    if (state.acknowledged && !details && !state.active) return
    if (!state.acknowledged || state.active) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(phaseLabel(state.phase), style = MaterialTheme.typography.titleSmall,
                color = if (state.phase in listOf(TransferPhase.ERROR, TransferPhase.INTERRUPTED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (state.currentName.isNotEmpty()) Text(state.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) {
            if (state.currentSource.isNotEmpty()) Text("Source : ${state.currentSource}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (state.destination.isNotEmpty()) Text("Destination : ${state.destination}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (state.active) {
                val fraction = state.copyFraction
                if (state.phase == TransferPhase.COPYING && fraction != null)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!compact) {
            Text("${state.completedFiles} fichiers terminés et vérifiés · " +
                (state.totalFiles?.let { "${(it - state.completedFiles).coerceAtLeast(0)} restants" } ?: "total en préparation ou inconnu"),
                style = MaterialTheme.typography.bodySmall)
            Text("${state.processedRoots}/${state.totalRoots} éléments traités · ${state.results.count { it.status == ItemStatus.FAILED }} erreurs",
                style = MaterialTheme.typography.bodySmall)
            if (state.phase != TransferPhase.PREPARING) {
                Text("Copie : ${FileUtils.formatSize(state.bytes)}" + (state.totalBytes?.let { " / ${FileUtils.formatSize(it)}" } ?: " · volume total inconnu"),
                    style = MaterialTheme.typography.bodySmall)
                if (state.phase == TransferPhase.COPYING) {
                    state.copyFraction?.let { Text("${(it * 100).toInt()} % copiés · vérification non comprise", style = MaterialTheme.typography.bodySmall) }
                    state.speed?.let { Text("${FileUtils.formatSize(it)}/s", style = MaterialTheme.typography.bodySmall) }
                    state.etaSeconds?.let { Text("Copie restante estimée : ${it}s · hors vérification", style = MaterialTheme.typography.bodySmall) }
                }
            }
            }
            if (state.cancelRequested && state.active) Text("Annulation demandée ; attente de l’arrêt réel.", style = MaterialTheme.typography.bodySmall)
            if (!state.active) state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { details = true; if (!state.active) dismiss() }) { Text("Détails") }
                if (state.active) TextButton(onClick = cancel, enabled = !state.cancelRequested) { Text("Annuler") }
                else TextButton(onClick = dismiss) { Text("Fermer") }
            }
        }
    }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Copie et déplacement") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(phaseLabel(state.phase), style = MaterialTheme.typography.titleSmall)
                    if (state.currentName.isNotEmpty()) Text(state.currentName)
                    if (state.currentSource.isNotEmpty()) Text("Source : ${state.currentSource}", style = MaterialTheme.typography.bodySmall)
                    if (state.destination.isNotEmpty()) Text("Destination : ${state.destination}", style = MaterialTheme.typography.bodySmall)
                    if (state.active && state.phase == TransferPhase.COPYING)
                        Text("Fichier courant : ${FileUtils.formatSize(state.fileBytes)}" + (state.fileTotal?.let { " / ${FileUtils.formatSize(it)}" } ?: " · taille inconnue"))
                    Text("${state.completedFiles} fichiers terminés et vérifiés / ${state.totalFiles ?: "total inconnu"}")
                    Text("${state.processedRoots}/${state.totalRoots} éléments traités")
                    Text("Copie : ${FileUtils.formatSize(state.bytes)}" + (state.totalBytes?.let { " / ${FileUtils.formatSize(it)}" } ?: " · volume total inconnu"))
                    if (state.phase == TransferPhase.COPYING) {
                        state.copyFraction?.let { Text("${(it * 100).toInt()} % copiés · vérification non comprise") }
                        state.speed?.let { Text("${FileUtils.formatSize(it)}/s") }
                        state.etaSeconds?.let { Text("Copie restante estimée : ${it}s · hors vérification") }
                    }
                    state.notice?.let { Text(it) }
                    Text("Vérification immédiate uniquement. Persistance après éjection et reconnexion non testée. Pas de reprise automatique après fermeture.", style = MaterialTheme.typography.bodySmall)
                }
                items(state.results) { result ->
                    HorizontalDivider()
                    Text(result.source.name, style = MaterialTheme.typography.titleSmall)
                    Text(when (result.status) {
                        ItemStatus.DONE -> "Terminé et vérifié"
                        ItemStatus.FAILED -> "Erreur"
                        ItemStatus.CANCELLED -> "Non démarré"
                        ItemStatus.UNCERTAIN -> "Interrompu : résultat à contrôler"
                    }, color = if (result.status == ItemStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    result.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Fermer les détails") } })
}

private fun phaseLabel(phase: TransferPhase) = when (phase) {
    TransferPhase.PREPARING -> "Préparation"
    TransferPhase.COPYING -> "Copie en cours"
    TransferPhase.VERIFYING -> "Vérification"
    TransferPhase.PUBLISHING -> "Publication · création et vérification de la destination"
    TransferPhase.DELETING -> "Copie vérifiée · suppression de la source"
    TransferPhase.DONE -> "Terminé"
    TransferPhase.CANCEL_REQUESTED -> "Annulation demandée"
    TransferPhase.CANCELLED -> "Annulé · opération arrêtée"
    TransferPhase.ERROR -> "Erreur ou résultat indéterminé"
    TransferPhase.INTERRUPTED -> "Transfert interrompu · contrôle nécessaire"
}
