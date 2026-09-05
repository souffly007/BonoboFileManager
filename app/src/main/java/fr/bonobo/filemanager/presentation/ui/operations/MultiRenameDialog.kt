package fr.bonobo.filemanager.presentation.ui.operations

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MultiRenameDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (prefix: String, suffix: String, find: String, replace: String, counter: Int?) -> Unit
) {
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var addCounter by remember { mutableStateOf(false) }
    var startCounter by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommage par lot ($count fichiers)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Préfixe") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = suffix, onValueChange = { suffix = it }, label = { Text("Suffixe") }, modifier = Modifier.fillMaxWidth())
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = find, onValueChange = { find = it }, label = { Text("Chercher") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = replace, onValueChange = { replace = it }, label = { Text("Remplacer") }, modifier = Modifier.weight(1f))
                }
                
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = addCounter, onCheckedChange = { addCounter = it })
                    Text("Ajouter un compteur (01_, 02_, ...)")
                }
                
                if (addCounter) {
                    OutlinedTextField(value = startCounter, onValueChange = { startCounter = it }, label = { Text("Commencer à") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(prefix, suffix, find, replace, if (addCounter) startCounter.toIntOrNull() ?: 1 else null)
            }) {
                Text("Appliquer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
