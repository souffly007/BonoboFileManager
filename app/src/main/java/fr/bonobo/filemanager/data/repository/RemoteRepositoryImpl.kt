package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.data.local.dao.RemoteConnectionDao
import fr.bonobo.filemanager.data.local.entity.RemoteConnectionEntity
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.domain.repository.IRemoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteRepositoryImpl @Inject constructor(
    private val dao: RemoteConnectionDao
) : IRemoteRepository {

    override fun getAllConnections(): Flow<List<RemoteConnection>> {
        return dao.getAllConnections().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveConnection(connection: RemoteConnection) {
        dao.insertConnection(connection.toEntity())
    }

    override suspend fun saveAll(connections: List<RemoteConnection>) {
        dao.insertAll(connections.map { it.toEntity() })
    }

    override suspend fun deleteConnection(connection: RemoteConnection) {
        dao.deleteConnection(connection.toEntity())
    }

    private fun RemoteConnectionEntity.toDomain() = RemoteConnection(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        pass = pass,
        type = type,
        share = share
    )

    private fun RemoteConnection.toEntity() = RemoteConnectionEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        pass = pass,
        type = type,
        share = share
    )
}
