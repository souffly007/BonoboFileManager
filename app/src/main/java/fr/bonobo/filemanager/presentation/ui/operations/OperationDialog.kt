package fr.bonobo.filemanager.presentation.ui.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.model.FileItem
import java.io.File

@Composable
fun RenameDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nom") }
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name != item.name
            ) {
                Text("Renommer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

@Composable
fun DeleteDialog(
    item: FileItem,
    isTrashView: Boolean,
    onDismiss: () -> Unit,
    onConfirmPermanent: () -> Unit,
    onMoveToTrash: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(if (isTrashView) "Gestion Corbeille" else "Supprimer le fichier") 
        },
        text = {
            Text(
                if (isTrashView) "Que souhaitez-vous faire de « ${item.name} » ?"
                else "Souhaitez-vous déplacer « ${item.name} » dans la corbeille ou le supprimer définitivement ?"
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isTrashView) {
                    Button(
                        onClick = onMoveToTrash, // On réutilise onMoveToTrash pour Restaurer dans cette vue
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restaurer le fichier")
                    }
                } else {
                    Button(
                        onClick = onMoveToTrash,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mettre à la corbeille")
                    }
                }
                
                OutlinedButton(
                    onClick = onConfirmPermanent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isTrashView) "Supprimer définitivement" else "Suppression directe")
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Annuler")
                }
            }
        }
    )
}

@Composable
fun MultiDeleteDialog(
    count: Int,
    isTrashView: Boolean,
    onDismiss: () -> Unit,
    onConfirmPermanent: () -> Unit,
    onMoveToTrash: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(if (isTrashView) "Suppression définitive" else "Supprimer les éléments") 
        },
        text = {
            Text(
                if (isTrashView) "Voulez-vous vraiment supprimer définitivement ces $count éléments ? Cette action est irréversible."
                else "Souhaitez-vous déplacer ces $count éléments dans la corbeille ou les supprimer définitivement ?"
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isTrashView) {
                    Button(
                        onClick = onMoveToTrash,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mettre à la corbeille")
                    }
                }
                
                OutlinedButton(
                    onClick = onConfirmPermanent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isTrashView) "Supprimer définitivement" else "Suppression directe")
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Annuler")
                }
            }
        }
    )
}

@Composable
fun CompressDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { 
        mutableStateOf(item.name.removeSuffix(".${File(item.path).extension}")) 
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compresser") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nom de l'archive (.zip)") }
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Compresser")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
