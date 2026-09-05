package fr.bonobo.filemanager.domain.repository

import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.flow.Flow

interface IRemoteRepository {
    fun getAllConnections(): Flow<List<RemoteConnection>>
    suspend fun saveConnection(connection: RemoteConnection)
    suspend fun saveAll(connections: List<RemoteConnection>)
    suspend fun deleteConnection(connection: RemoteConnection)
}
