package fr.bonobo.filemanager.presentation.ui.network

import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.service.FtpService
import fr.bonobo.filemanager.util.ConfigParser
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onConnect: (RemoteConnection) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isRunning by FtpService.isRunning.collectAsState()
    val ipAddress = getIpAddress(context)
    
    val state by viewModel.uiState.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    var connectionToDelete by remember { mutableStateOf<RemoteConnection?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (connectionToDelete != null) {
        AlertDialog(
            onDismissRequest = { connectionToDelete = null },
            title = { Text("Supprimer la connexion") },
            text = { Text("Voulez-vous vraiment supprimer « ${connectionToDelete?.name} » ? Cette action est définitive.") },
            confirmButton = {
                Button(
                    onClick = {
                        connectionToDelete?.let { viewModel.removeConnection(it) }
                        connectionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { connectionToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Réseau") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.Home, contentDescription = "Accueil")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isScanning,
            onRefresh = { if (selectedTab == 1) viewModel.startLanScan(ipAddress) },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("Serveur FTP", modifier = Modifier.padding(16.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("Serveurs Distants", modifier = Modifier.padding(16.dp))
                    }
                }

                if (selectedTab == 0) {
                    FtpServerView(isRunning, ipAddress, context)
                } else {
                    RemoteClientView(
                        connections = state.connections,
                        scannedIps = state.scannedIps,
                        isScanning = state.isScanning,
                        onScan = { viewModel.startLanScan(ipAddress) },
                        onConnect = onConnect,
                        onAddConnection = viewModel::addConnection,
                        onAddAllConnections = viewModel::addAllConnections,
                        onRemoveConnection = { connectionToDelete = it },
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FtpServerView(isRunning: Boolean, ipAddress: String, context: android.content.Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lan,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Accès PC (FTP)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Text(
            text = "Accédez aux fichiers de votre téléphone depuis un ordinateur sur le même réseau WiFi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRunning) "Le serveur est ACTIF" else "Le serveur est ARRÊTÉ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ftp://$ipAddress:2121",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Utilisateur: bonobo / MDP: bonobo",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { 
                if (isRunning) FtpService.stop(context) 
                else FtpService.start(context) 
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) 
                    MaterialTheme.colorScheme.error 
                else 
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.PowerOff else Icons.Default.Power,
                    contentDescription = null
                )
                Text(if (isRunning) "Arrêter le serveur" else "Démarrer le serveur")
            }
        }
    }
}

@Composable
fun RemoteClientView(
    connections: List<RemoteConnection>,
    scannedIps: List<String>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onConnect: (RemoteConnection) -> Unit,
    onAddConnection: (RemoteConnection) -> Unit,
    onAddAllConnections: (List<RemoteConnection>) -> Unit,
    onRemoveConnection: (RemoteConnection) -> Unit,
    onShowMessage: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedScannedIp by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val imported = ConfigParser.parseConnections(stream)
                    if (imported.isNotEmpty()) {
                        onAddAllConnections(imported)
                    } else {
                        onShowMessage("Aucune connexion valide trouvée dans le fichier")
                    }
                }
            }.onFailure { e ->
                onShowMessage("Erreur lors de la lecture : ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Serveurs Distants",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        TextButton(onClick = { importLauncher.launch("*/*") }) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Importer XML")
        }

        // Section Scan Réseau
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanner le réseau")
                }
            }
        }

        if (scannedIps.isNotEmpty()) {
            Text(
                text = "Serveurs trouvés (Scan)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scannedIps) { ip ->
                    AssistChip(
                        onClick = { selectedScannedIp = ip; showAddDialog = true },
                        label = { Text(ip) },
                        leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }

        Text(
            text = "Connectez-vous à votre Seedbox, NAS ou Cloud (FTP, SMB, SFTP).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (connections.isEmpty()) {
                item {
                    Text(
                        text = "Aucune connexion enregistrée",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(connections) { conn ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onConnect(conn) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(text = conn.name, style = MaterialTheme.typography.titleMedium)
                                Text(text = "${conn.host}:${conn.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onRemoveConnection(conn) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ajouter un serveur")
        }
    }

    if (showAddDialog) {
        AddRemoteDialog(
            initialHost = selectedScannedIp ?: "",
            onDismiss = { showAddDialog = false; selectedScannedIp = null },
            onConfirm = { 
                onAddConnection(it)
                showAddDialog = false
                selectedScannedIp = null
            }
        )
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
