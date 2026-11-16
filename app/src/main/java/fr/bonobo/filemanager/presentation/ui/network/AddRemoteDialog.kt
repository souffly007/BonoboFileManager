package fr.bonobo.filemanager.presentation.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.RemoteConnection

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddRemoteDialog(
    initialHost: String = "",
    initialConnection: RemoteConnection? = null,
    smbOnly: Boolean = false,
    fixedType: ConnectionType? = null,
    onDismiss: () -> Unit,
    onConfirm: (RemoteConnection) -> Unit
) {
    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name ?: "") }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host ?: initialHost) }
    var port by remember(initialConnection, smbOnly) {
        mutableStateOf((if (fixedType == ConnectionType.SMB || smbOnly) 445 else (initialConnection?.port ?: if (fixedType == ConnectionType.SFTP) 22 else if (fixedType == ConnectionType.WEBDAV) 443 else 21)).toString())
    }
    var user by remember(initialConnection) { mutableStateOf(initialConnection?.user ?: "") }
    var pass by remember(initialConnection) { mutableStateOf(initialConnection?.pass ?: "") }
    var share by remember(initialConnection) { mutableStateOf(initialConnection?.share ?: "") }
    var type by remember(initialConnection, smbOnly) {
        mutableStateOf(fixedType ?: if (smbOnly) ConnectionType.SMB
        else initialConnection?.type?.takeUnless { it == ConnectionType.FTP } ?: ConnectionType.FTPS)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialConnection == null) "Ajouter un serveur" else "Modifier le serveur") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom (ex: Ma Seedbox)") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Hôte / IP") }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true)
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Mot de passe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                
                if (type == ConnectionType.SMB || type == ConnectionType.WEBDAV) {
                    OutlinedTextField(
                        value = share, 
                        onValueChange = { share = it }, 
                        label = { Text(if (type == ConnectionType.SMB) "Partage / dossier (ex: public ou c/Users/Nico/Desktop)" else "Chemin WebDAV (ex: remote.php/dav/files/nom)") }, 
                        singleLine = true, 
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (!smbOnly && fixedType == null) {
                    Text("Type de connexion", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConnectionType.entries.filter { it != ConnectionType.FTP }.forEach { connType ->
                            FilterChip(
                                selected = type == connType,
                                onClick = {
                                    type = connType
                                    if (port == "21" && connType == ConnectionType.SMB) port = "445"
                                    if (port == "445" && connType == ConnectionType.SFTP) port = "22"
                                    if ((port == "21" || port == "22") && connType == ConnectionType.WEBDAV) port = "443"
                                },
                                label = { Text(connType.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    onConfirm(RemoteConnection(
                        id = initialConnection?.id ?: 0,
                        name = name, 
                        host = host, 
                        port = port.toIntOrNull() ?: if (type == ConnectionType.SFTP) 22 else if (type == ConnectionType.SMB) 445 else if (type == ConnectionType.WEBDAV) 443 else 21, 
                        user = user, 
                        pass = pass, 
                        type = type, 
                        share = share.ifBlank { null }
                    ))
                },
                enabled = name.isNotBlank() && host.isNotBlank()
            ) {
                Text(if (initialConnection == null) "Ajouter" else "Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
