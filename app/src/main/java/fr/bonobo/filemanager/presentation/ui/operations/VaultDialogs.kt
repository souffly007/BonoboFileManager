package fr.bonobo.filemanager.presentation.ui.operations

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.util.BiometricUtils
import androidx.fragment.app.FragmentActivity

@Composable
fun VaultAccessDialog(
    storedPassword: String?,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onSetPassword: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val isFirstTime = storedPassword == null
    val context = LocalContext.current
    val canBiometric = remember { BiometricUtils.canAuthenticate(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFirstTime) "Configurer le coffre-fort" else "Accès au coffre-fort") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isFirstTime) {
                    Text("Choisissez un mot de passe maître pour sécuriser vos fichiers.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Nouveau mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = { Text("Confirmer le mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Mot de passe maître") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (canBiometric) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                BiometricUtils.showBiometricPrompt(
                                    activity = context as FragmentActivity,
                                    onSuccess = { onSuccess() },
                                    onError = { error = it }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Utiliser l'empreinte")
                        }
                    }
                }
                
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (isFirstTime) {
                    if (password.length < 4) {
                        error = "Le mot de passe doit faire au moins 4 caractères"
                    } else if (password != confirmPassword) {
                        error = "Les mots de passe ne correspondent pas"
                    } else {
                        onSetPassword(password)
                        onSuccess()
                    }
                } else {
                    if (password == storedPassword) {
                        onSuccess()
                    } else {
                        error = "Mot de passe incorrect"
                    }
                }
            }) {
                Text(if (isFirstTime) "Créer" else "Déverrouiller")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
