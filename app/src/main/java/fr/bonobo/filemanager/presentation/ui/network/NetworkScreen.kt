package fr.bonobo.filemanager.presentation.ui.network

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import android.net.wifi.WifiManager
import android.provider.OpenableColumns
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.service.FtpService
import fr.bonobo.filemanager.service.HttpsShareService
import fr.bonobo.filemanager.service.DropboxCloudManager
import fr.bonobo.filemanager.service.DropboxItem
import fr.bonobo.filemanager.service.OneDriveCloudManager
import fr.bonobo.filemanager.service.OneDriveItem
import fr.bonobo.filemanager.service.GoogleDriveCloudManager
import fr.bonobo.filemanager.service.GoogleDriveItem
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
    val ftpPassword by FtpService.password.collectAsState()
    val httpsState by HttpsShareService.state.collectAsState()
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
                NetworkSectionMenus(
                    selectedTab = selectedTab,
                    onSelectedTab = { selectedTab = it }
                )

                when (selectedTab) {
                    0 -> FtpServerView(isRunning, ipAddress, ftpPassword, context)
                    1 -> RemoteClientView(
                        connections = state.connections,
                        scannedIps = state.scannedIps,
                        isScanning = state.isScanning,
                        onScan = { viewModel.startLanScan(ipAddress) },
                        onConnect = onConnect,
                        onAddConnection = viewModel::addConnection,
                        onAddAllConnections = viewModel::addAllConnections,
                        onRemoveConnection = { connectionToDelete = it },
                        onUpdateConnection = viewModel::updateConnection,
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                    2 -> HttpsShareView(httpsState, ipAddress, context)
                    3, 4, 5 -> CloudsView(context, selectedTab)
                }
            }
        }
    }
}

@Composable
private fun NetworkSectionMenus(
    selectedTab: Int,
    onSelectedTab: (Int) -> Unit
) {
    val family = when (selectedTab) {
        0, 1 -> "Réseaux locaux"
        2 -> "Serveurs distants"
        else -> "Clouds"
    }

    val familyItems = listOf("Réseaux locaux", "Serveurs distants", "Clouds")
    val serviceItems = when (family) {
        "Réseaux locaux" -> listOf("Serveur FTPS", "Découvrir les PC")
        "Serveurs distants" -> listOf("Partage HTTPS")
        else -> listOf("Dropbox", "OneDrive", "Google Drive")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NetworkDropdown(
            label = "Catégorie réseau",
            value = family,
            options = familyItems,
            onSelected = { selected ->
                when (selected) {
                    "Réseaux locaux" -> onSelectedTab(0)
                    "Serveurs distants" -> onSelectedTab(2)
                    "Clouds" -> onSelectedTab(if (selectedTab >= 3) selectedTab else 3)
                }
            }
        )

        NetworkDropdown(
            label = "Service",
            value = when (selectedTab) {
                0 -> "Serveur FTPS"
                1 -> "Découvrir les PC"
                2 -> "Partage HTTPS"
                3 -> "Dropbox"
                4 -> "OneDrive"
                else -> "Google Drive"
            },
            options = serviceItems,
            onSelected = { selected ->
                when (selected) {
                    "Serveur FTPS" -> onSelectedTab(0)
                    "Découvrir les PC" -> onSelectedTab(1)
                    "Partage HTTPS" -> onSelectedTab(2)
                    "Dropbox" -> onSelectedTab(3)
                    "OneDrive" -> onSelectedTab(4)
                    "Google Drive" -> onSelectedTab(5)
                }
            }
        )
    }
}

@Composable
private fun NetworkDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Ouvrir le menu")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun HttpsShareView(
    httpsState: fr.bonobo.filemanager.service.HttpsShareState,
    ipAddress: String,
    context: android.content.Context
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Partage sécurisé HTTPS",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            "Ouvrez l'adresse affichée dans le navigateur du PC. Le partage utilise TLS et un code temporaire.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (httpsState.running) "Serveur HTTPS ACTIF" else "Serveur HTTPS arrêté",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (httpsState.running) {
                    Text(
                        "https://$ipAddress:${httpsState.port}/?token=${httpsState.token}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Code d'accès : ${httpsState.token}")
                    httpsState.fingerprint?.let {
                        Text("Certificat SHA-256 : $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                httpsState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Button(
            onClick = {
                if (httpsState.running) HttpsShareService.stop(context)
                else HttpsShareService.start(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                if (httpsState.running) Icons.Default.PowerOff else Icons.Default.Power,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (httpsState.running) "Arrêter le serveur HTTPS" else "Démarrer le serveur HTTPS")
        }
    }
}

@Composable
fun FtpServerView(
    isRunning: Boolean,
    ipAddress: String,
    ftpPassword: String?,
    context: android.content.Context
) {
    val fingerprint by FtpService.fingerprint.collectAsState()
    val serverError by FtpService.error.collectAsState()
    val isStarting by FtpService.isStarting.collectAsState()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                password = ""
                confirmation = ""
                passwordError = null
            },
            title = { Text("Configurer le mot de passe FTPS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ce mot de passe sera demandé pour accéder aux fichiers depuis le PC.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; passwordError = null },
                        label = { Text("Mot de passe FTPS") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it; passwordError = null },
                        label = { Text("Confirmer le mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    passwordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when {
                        password.length < 8 ->
                            passwordError = "Utilisez au moins 8 caractères."
                        password != confirmation ->
                            passwordError = "Les deux mots de passe ne correspondent pas."
                        else -> {
                            FtpService.start(context, password)
                            password = ""
                            confirmation = ""
                            passwordError = null
                            showPasswordDialog = false
                        }
                    }
                }) {
                    Text("Démarrer")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    password = ""
                    confirmation = ""
                    passwordError = null
                }) { Text("Annuler") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            text = "Accès PC sécurisé (FTPS)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Text(
            text = "Dossier partagé : Téléchargements/Bonobo-Partage. Dans FileZilla : FTP, chiffrement « Exiger une connexion FTP explicite sur TLS », port 2121 et mode passif.",
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
                    text = if (isStarting) "Démarrage sécurisé…"
                    else if (isRunning) "Le serveur FTPS est ACTIF"
                    else "Le serveur est ARRÊTÉ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$ipAddress:2121",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Utilisateur: bonobo / MDP: ${ftpPassword ?: "indisponible"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    fingerprint?.let { value ->
                        Spacer(Modifier.height(12.dp))
                        Text("Certificat SHA-256", style = MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Au premier accès, comparez cette empreinte avec celle affichée par FileZilla avant d'accepter le certificat. Un avertissement de nom/adresse est normal pour ce certificat local.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        serverError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            enabled = !isStarting,
            onClick = {
                if (isRunning) FtpService.stop(context)
                else showPasswordDialog = true
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
    onUpdateConnection: (RemoteConnection) -> Unit,
    onShowMessage: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var connectionToEdit by remember { mutableStateOf<RemoteConnection?>(null) }
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
            imageVector = Icons.Default.DeviceHub,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scannedIps) { ip ->
                    AssistChip(
                        onClick = { selectedScannedIp = ip; showAddDialog = true },
                        label = { Text(ip) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        Text(
            text = "Connectez-vous à votre Seedbox, NAS ou Cloud (FTPS, SMB, SFTP).",
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
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)) {
                                Text(text = conn.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${conn.host}:${conn.port}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { onRemoveConnection(conn) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Supprimer",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            IconButton(onClick = { connectionToEdit = conn }) {
                                Icon(Icons.Default.Edit, contentDescription = "Modifier")
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
    connectionToEdit?.let { connection ->
        AddRemoteDialog(
            initialConnection = connection,
            onDismiss = { connectionToEdit = null },
            onConfirm = { onUpdateConnection(it); connectionToEdit = null }
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

@Composable
private fun CloudsView(context: android.content.Context, selectedTab: Int) {
    when (selectedTab) {
        4 -> OneDriveCloudView(context)
        5 -> GoogleDriveCloudView(context)
        else -> DropboxCloudView(context)
    }
}

@Composable
private fun OneDriveCloudView(context: android.content.Context) {
    val state by OneDriveCloudManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val folderStack = remember { mutableStateListOf<String?>() }
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<OneDriveItem?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = selectedFileName(context, uri)
        scope.launch {
            runCatching { OneDriveCloudManager.upload(context, uri, name, currentFolderId) }
                .onSuccess { path -> OneDriveCloudManager.setNotice("Envoyé dans $path") }
                .onFailure { error -> OneDriveCloudManager.setError("Envoi impossible : ${error.message}") }
        }
    }

    LaunchedEffect(Unit) {
        if (OneDriveCloudManager.isConnected(context)) {
            OneDriveCloudManager.listFolder(context, currentFolderId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "OneDrive",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            "Connexion Microsoft sécurisée. Bonobo ne demande jamais votre mot de passe Microsoft.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (!state.connected) {
            Button(
                onClick = { OneDriveCloudManager.startLogin(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connecter OneDrive")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { scope.launch { OneDriveCloudManager.listFolder(context, currentFolderId) } },
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Actualiser")
                }
                OutlinedButton(
                    onClick = { OneDriveCloudManager.disconnect(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Déconnecter")
                }
            }
            OutlinedButton(
                onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Envoyer un fichier ici")
            }
            Button(
                onClick = { showCreateFolder = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Créer un dossier")
            }

            if (folderStack.isNotEmpty()) {
                TextButton(onClick = {
                    currentFolderId = folderStack.removeAt(folderStack.lastIndex)
                    scope.launch { OneDriveCloudManager.listFolder(context, currentFolderId) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Dossier parent")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (!state.loading) {
                                if (item.folder) {
                                    folderStack.add(currentFolderId)
                                    currentFolderId = item.id
                                    scope.launch { OneDriveCloudManager.listFolder(context, item.id) }
                                } else {
                                    scope.launch {
                                        runCatching { OneDriveCloudManager.download(context, item) }
                                            .onSuccess { path -> OneDriveCloudManager.setNotice("Téléchargé dans $path") }
                                            .onFailure { error -> OneDriveCloudManager.setError("Téléchargement impossible : ${error.message}") }
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (item.folder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            Text(
                                if (item.folder) "Dossier — toucher pour ouvrir"
                                else "${formatDropboxSize(item.size)} — toucher pour télécharger"
                            )
                        },
                        trailingContent = {
                            Row {
                                if (!item.folder) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { OneDriveCloudManager.download(context, item) }
                                                .onSuccess { path -> OneDriveCloudManager.setNotice("Téléchargé dans $path") }
                                                .onFailure { error -> OneDriveCloudManager.setError("Téléchargement impossible : ${error.message}") }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Télécharger")
                                    }
                                }
                                IconButton(onClick = { deleteTarget = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                scope.launch {
                    runCatching { OneDriveCloudManager.createFolder(context, currentFolderId, name) }
                        .onSuccess { OneDriveCloudManager.setNotice("Dossier créé") }
                        .onFailure { error -> OneDriveCloudManager.setError("Création impossible : ${error.message}") }
                }
            }
        )
    }
    deleteTarget?.let { item ->
        DeleteItemDialog(
            item.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    runCatching { OneDriveCloudManager.delete(context, item) }
                        .onSuccess { OneDriveCloudManager.setNotice("Élément supprimé") }
                        .onFailure { error -> OneDriveCloudManager.setError("Suppression impossible : ${error.message}") }
                }
            }
        )
    }
}

@Composable
private fun GoogleDriveCloudView(context: android.content.Context) {
    val state by GoogleDriveCloudManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val folderStack = remember { mutableStateListOf<String?>() }
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GoogleDriveItem?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = selectedFileName(context, uri)
        scope.launch {
            runCatching { GoogleDriveCloudManager.upload(context, uri, name, currentFolderId) }
                .onSuccess { GoogleDriveCloudManager.setNotice("Fichier envoyé") }
                .onFailure { error -> GoogleDriveCloudManager.setError("Envoi impossible : ${error.message}") }
        }
    }

    LaunchedEffect(Unit) {
        if (GoogleDriveCloudManager.isConnected(context)) {
            GoogleDriveCloudManager.listFolder(context, currentFolderId)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Google Drive",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (!state.connected) {
            Button(
                onClick = { GoogleDriveCloudManager.startLogin(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connecter Google Drive")
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { scope.launch { GoogleDriveCloudManager.listFolder(context, currentFolderId) } },
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Actualiser")
                }
                OutlinedButton(
                    onClick = { GoogleDriveCloudManager.disconnect(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Déconnecter")
                }
            }
            OutlinedButton(
                onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Envoyer un fichier ici")
            }
            Button(
                onClick = { showCreateFolder = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Créer un dossier")
            }
            if (folderStack.isNotEmpty()) {
                TextButton(onClick = {
                    currentFolderId = folderStack.removeAt(folderStack.lastIndex)
                    scope.launch { GoogleDriveCloudManager.listFolder(context, currentFolderId) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Dossier parent")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (item.folder) {
                                folderStack.add(currentFolderId)
                                currentFolderId = item.id
                                scope.launch { GoogleDriveCloudManager.listFolder(context, item.id) }
                            } else {
                                scope.launch {
                                    runCatching { GoogleDriveCloudManager.download(context, item) }
                                        .onSuccess { GoogleDriveCloudManager.setNotice("Fichier téléchargé") }
                                        .onFailure { GoogleDriveCloudManager.setError("Téléchargement impossible : ${it.message}") }
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (item.folder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            Text(
                                if (item.folder) "Dossier — toucher pour ouvrir"
                                else "${formatDropboxSize(item.size)} — toucher pour télécharger"
                            )
                        },
                        trailingContent = {
                            Row {
                                if (!item.folder) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { GoogleDriveCloudManager.download(context, item) }
                                                .onSuccess { GoogleDriveCloudManager.setNotice("Fichier téléchargé") }
                                                .onFailure { GoogleDriveCloudManager.setError("Téléchargement impossible : ${it.message}") }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Télécharger")
                                    }
                                }
                                IconButton(onClick = { deleteTarget = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                scope.launch {
                    runCatching { GoogleDriveCloudManager.createFolder(context, currentFolderId, name) }
                        .onSuccess { GoogleDriveCloudManager.setNotice("Dossier créé") }
                        .onFailure { GoogleDriveCloudManager.setError("Création impossible : ${it.message}") }
                }
            }
        )
    }
    deleteTarget?.let { item ->
        DeleteItemDialog(
            item.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    runCatching { GoogleDriveCloudManager.delete(context, item) }
                        .onSuccess { GoogleDriveCloudManager.setNotice("Élément supprimé") }
                        .onFailure { GoogleDriveCloudManager.setError("Suppression impossible : ${it.message}") }
                }
            }
        )
    }
}

@Composable
private fun DropboxCloudView(context: android.content.Context) {
    val state by DropboxCloudManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var currentPath by rememberSaveable { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DropboxItem?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = selectedFileName(context, uri)
        scope.launch {
            runCatching { DropboxCloudManager.upload(context, uri, name, currentPath) }
                .onSuccess { path -> DropboxCloudManager.setNotice("Envoyé dans $path") }
                .onFailure { error -> DropboxCloudManager.setError("Envoi impossible : ${error.message}") }
        }
    }

    LaunchedEffect(Unit) {
        if (DropboxCloudManager.isConnected(context)) {
            DropboxCloudManager.listFolder(context, currentPath)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Dropbox",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            "Connexion OAuth sécurisée. Bonobo ne demande jamais votre mot de passe Dropbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (!state.connected) {
            Button(
                onClick = { DropboxCloudManager.startLogin(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connecter Dropbox")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { scope.launch { DropboxCloudManager.listRoot(context) } },
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Actualiser")
                }
                OutlinedButton(
                    onClick = { DropboxCloudManager.disconnect(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Déconnecter")
                }
            }

            if (currentPath.isNotEmpty()) {
                TextButton(onClick = {
                    currentPath = currentPath.substringBeforeLast('/', "")
                    scope.launch { DropboxCloudManager.listFolder(context, currentPath) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Dossier parent")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.items, key = { it.path }) { item ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (!state.loading) {
                                if (item.folder) {
                                    currentPath = item.apiPath
                                    scope.launch { DropboxCloudManager.listFolder(context, item.apiPath) }
                                } else {
                                    scope.launch {
                                        runCatching { DropboxCloudManager.download(context, item) }
                                            .onSuccess { path ->
                                                DropboxCloudManager.setNotice("Téléchargé dans $path")
                                            }
                                            .onFailure { error ->
                                                DropboxCloudManager.setError("Téléchargement impossible : ${error.message}")
                                            }
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (item.folder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            Text(
                                if (item.folder) "Dossier — toucher pour ouvrir"
                                else "${formatDropboxSize(item.size)} — toucher pour télécharger"
                            )
                        },
                        trailingContent = {
                            Row {
                                if (!item.folder) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { DropboxCloudManager.download(context, item) }
                                                .onSuccess { path -> DropboxCloudManager.setNotice("Téléchargé dans $path") }
                                                .onFailure { error -> DropboxCloudManager.setError("Téléchargement impossible : ${error.message}") }
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Télécharger")
                                    }
                                }
                                IconButton(onClick = { deleteTarget = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                                }
                            }
                        }
                    )
                }
            }

            OutlinedButton(
                onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Envoyer un fichier ici")
            }
            Button(
                onClick = { showCreateFolder = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Créer un dossier")
            }
        }
    }

    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                scope.launch {
                    runCatching { DropboxCloudManager.createFolder(context, currentPath, name) }
                        .onSuccess { DropboxCloudManager.setNotice("Dossier créé") }
                        .onFailure { error -> DropboxCloudManager.setError("Création impossible : ${error.message}") }
                }
            }
        )
    }
    deleteTarget?.let { item ->
        DeleteItemDialog(
            item.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    runCatching { DropboxCloudManager.delete(context, item) }
                        .onSuccess { DropboxCloudManager.setNotice("Élément supprimé") }
                        .onFailure { error -> DropboxCloudManager.setError("Suppression impossible : ${error.message}") }
                }
            }
        )
    }
}

private fun formatDropboxSize(size: Long): String = when {
    size < 1024L -> "$size o"
    size < 1024L * 1024L -> "%.1f Ko".format(Locale.getDefault(), size / 1024.0)
    size < 1024L * 1024L * 1024L -> "%.1f Mo".format(Locale.getDefault(), size / (1024.0 * 1024.0))
    else -> "%.1f Go".format(Locale.getDefault(), size / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun CreateFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créer un dossier") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du dossier") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun DeleteItemDialog(itemName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer ?") },
        text = { Text("Voulez-vous vraiment supprimer « $itemName » ?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private fun selectedFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
    }
    return name
}