package fr.bonobo.filemanager.presentation.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.domain.repository.IRemoteRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkUiState(
    val connections: List<RemoteConnection> = emptyList(),
    val scannedIps: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: IRemoteRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    private val _scannedIps = MutableStateFlow<List<String>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    
    val uiState: StateFlow<NetworkUiState> = combine(
        repository.getAllConnections(),
        _scannedIps,
        _isScanning,
        _message
    ) { connections, scanned, scanning, message ->
        NetworkUiState(connections, scanned, scanning, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkUiState())

    fun startLanScan(baseIp: String) {
        viewModelScope.launch {
            _isScanning.value = true
            _scannedIps.value = emptyList()
            try {
                val ips = fr.bonobo.filemanager.util.NetworkScanner.scanSubnet(baseIp)
                _scannedIps.value = ips
                if (ips.isEmpty()) _message.value = "Aucun serveur trouvé"
            } catch (e: Exception) {
                _message.value = "Erreur scan : ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun addConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            repository.saveConnection(connection)
        }
    }

    fun addAllConnections(connections: List<RemoteConnection>) {
        viewModelScope.launch {
            repository.saveAll(connections)
            _message.value = "${connections.size} serveurs importés"
        }
    }

    fun removeConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            repository.deleteConnection(connection)
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
