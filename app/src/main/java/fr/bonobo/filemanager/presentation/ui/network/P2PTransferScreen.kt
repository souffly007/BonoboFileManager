package fr.bonobo.filemanager.presentation.ui.network

import android.net.wifi.WifiManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.service.TransferServer
import fr.bonobo.filemanager.util.QrCodeUtils
import java.io.File
import java.security.SecureRandom
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PTransferScreen(
    filePath: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath) }
    val ipAddress = remember { getIpAddress(context) }
    val transferUrl = "http://$ipAddress:8080/"

    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var activePassword by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<TransferServer?>(null) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(server, owner) {
        val session = server
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session?.stop()
                server = null
                activePassword = null
                password = ""
                confirmation = ""
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session?.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfert Direct") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            if (activePassword == null) {
                Text(
                    "Choisissez le mot de passe avant de générer le QR code.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Mot de passe — 8 caractères minimum") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; error = null },
                    label = { Text("Confirmer le mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val generated = generatePassword()
                        password = generated
                        confirmation = generated
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Générer un mot de passe")
                }
                Button(
                    onClick = {
                        when {
                            password.length < 8 -> error = "Le mot de passe doit contenir au moins 8 caractères."
                            password != confirmation -> error = "Les deux mots de passe ne correspondent pas."
                            else -> {
                                val newServer = TransferServer(password)
                                newServer.start(file).onSuccess {
                                    server = newServer
                                    activePassword = password
                                    password = ""
                                    confirmation = ""
                                }.onFailure {
                                    newServer.stop()
                                    error = "Démarrage impossible : ${it.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Démarrer et générer le QR code")
                }
            } else {
                val qrBitmap = remember(transferUrl) { QrCodeUtils.generateQrCode(transferUrl) }
                Text(
                    "Scannez pour télécharger",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(20.dp))
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code de transfert",
                    modifier = Modifier.size(256.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                SelectionContainer {
                    Text(transferUrl, color = MaterialTheme.colorScheme.secondary)
                }
                Text("Utilisateur : bonobo", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text("Mot de passe : $activePassword", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Le navigateur demandera ces identifiants. Les deux appareils doivent être sur le même réseau Wi-Fi de confiance. HTTP ne chiffre ni les identifiants ni le fichier. Le transfert s’arrête en quittant cet écran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        server?.stop()
                        server = null
                        activePassword = null
                    }
                ) {
                    Text("Arrêter le transfert")
                }
            }
        }
    }
}

private fun generatePassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = SecureRandom()
    return buildString(12) {
        repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

private fun getIpAddress(context: android.content.Context): String {
    val wifiManager = context.applicationContext
        .getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
    val ipAddress = wifiManager.connectionInfo.ipAddress
    return String.format(
        Locale.getDefault(),
        "%d.%d.%d.%d",
        ipAddress and 0xff,
        ipAddress shr 8 and 0xff,
        ipAddress shr 16 and 0xff,
        ipAddress shr 24 and 0xff
    )
}

@Composable
fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { content() }
}
