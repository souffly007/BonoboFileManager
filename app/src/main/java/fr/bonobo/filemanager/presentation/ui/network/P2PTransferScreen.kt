package fr.bonobo.filemanager.presentation.ui.network

import android.net.wifi.WifiManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.service.TransferServer
import fr.bonobo.filemanager.util.QrCodeUtils
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PTransferScreen(
    filePath: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val file = remember { File(filePath) }
    val server = remember { TransferServer() }
    
    val ipAddress = remember { getIpAddress(context) }
    val transferUrl = "http://$ipAddress:8080"
    val qrBitmap = remember { QrCodeUtils.generateQrCode(transferUrl) }

    DisposableEffect(Unit) {
        server.start(file)
        onDispose { server.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfert Direct") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Scannez pour télécharger",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR Code de transfert",
                modifier = Modifier.size(256.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Ou ouvrez sur un navigateur :",
                style = MaterialTheme.typography.labelMedium
            )
            
            SelectionContainer {
                Text(
                    text = transferUrl,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Les deux appareils doivent être sur le même réseau WiFi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getIpAddress(context: android.content.Context): String {
    val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
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
