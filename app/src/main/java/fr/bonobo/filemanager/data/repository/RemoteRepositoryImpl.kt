package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.data.local.dao.RemoteConnectionDao
import fr.bonobo.filemanager.data.local.entity.RemoteConnectionEntity
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.domain.repository.IRemoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import fr.bonobo.filemanager.util.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteRepositoryImpl @Inject constructor(
    private val dao: RemoteConnectionDao,
    private val credentials: CredentialStore
) : IRemoteRepository {

    override fun getAllConnections(): Flow<List<RemoteConnection>> {
        return dao.getAllConnections().map { entities ->
            val securedEntities = entities.map { entity ->
                if (entity.credentialsEncrypted) entity else entity.copy(
                    pass = credentials.encrypt(entity.pass), credentialsEncrypted = true)
            }
            securedEntities.zip(entities).filter { !it.second.credentialsEncrypted }.forEach { (secured, original) ->
                dao.migratePassword(original.id, original.pass, secured.pass)
            }
            securedEntities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun saveConnection(connection: RemoteConnection) {
        dao.insertConnection(connection.toEntity())
    }

    override suspend fun updateConnection(connection: RemoteConnection) {
        require(connection.id > 0) { "Connexion invalide" }
        dao.insertConnection(connection.toEntity())
    }

    override suspend fun saveAll(connections: List<RemoteConnection>) {
        dao.insertAll(connections.map { it.toEntity() })
    }

    override suspend fun deleteConnection(connection: RemoteConnection) {
        dao.deleteById(connection.id)
    }

    private fun RemoteConnectionEntity.toDomain(): RemoteConnection {
        val decoded = runCatching { if (credentialsEncrypted) credentials.decrypt(pass) else pass }
        return RemoteConnection(
            id = id,
            name = if (decoded.isFailure) "$name (mot de passe à reconfigurer)" else name,
            host = host,
            port = port,
            user = user,
            pass = decoded.getOrDefault(""),
            type = type,
            share = share
        )
    }

    private fun RemoteConnection.toEntity() = RemoteConnectionEntity(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        pass = credentials.encrypt(pass),
        type = type,
        share = share,
        credentialsEncrypted = true
    )
}
