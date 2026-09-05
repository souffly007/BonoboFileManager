package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFileRepository @Inject constructor(
    private val ftpRepository: FtpClientRepository,
    private val smbRepository: SmbClientRepository
) {
    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> {
        return when (connection.type) {
            ConnectionType.FTP, ConnectionType.FTPS -> ftpRepository.listRemoteFiles(connection, path)
            ConnectionType.SMB -> smbRepository.listRemoteFiles(connection, path)
            else -> Result.failure(Exception("Type de connexion non supporté pour le moment"))
        }
    }

    suspend fun deleteRemoteFile(connection: RemoteConnection, path: String): Result<Unit> {
        return when (connection.type) {
            ConnectionType.FTP, ConnectionType.FTPS -> ftpRepository.deleteFile(connection, path)
            ConnectionType.SMB -> smbRepository.deleteFile(connection, path)
            else -> Result.failure(Exception("Suppression non supportée pour ce type de connexion"))
        }
    }
}
