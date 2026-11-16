package fr.bonobo.filemanager.presentation.ui.operations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import fr.bonobo.filemanager.domain.model.FileItem

@Composable
fun EncryptionDialog(
    item: FileItem,
    isDecrypt: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDecrypt) "Décrypter « ${item.name} »" else "Crypter « ${item.name} »") },
        text = {
            Column {
                Text(
                    text = if (isDecrypt) "Entrez le mot de passe pour décrypter le fichier." 
                          else "Le fichier sera chiffré en AES-GCM. Choisissez au moins 8 caractères et conservez votre mot de passe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (!isDecrypt) {
                    OutlinedTextField(value = confirmation, onValueChange = { confirmation = it },
                        label = { Text("Confirmer le mot de passe") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = if (isDecrypt) password.isNotEmpty() else password.length >= 8 && password == confirmation
            ) {
                Text(if (isDecrypt) "Décrypter" else "Crypter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
