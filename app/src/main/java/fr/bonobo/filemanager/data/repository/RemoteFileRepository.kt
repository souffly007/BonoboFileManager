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
            ConnectionType.FTPS -> ftpRepository.listRemoteFiles(connection, path)
            ConnectionType.FTP -> Result.failure(Exception("FTP classique est désactivé. Utilisez FTPS sécurisé."))
            ConnectionType.SMB -> smbRepository.listRemoteFiles(connection, path)
            else -> Result.failure(Exception("Type de connexion non supporté pour le moment"))
        }
    }

    suspend fun deleteRemoteFile(connection: RemoteConnection, path: String): Result<Unit> {
        return when (connection.type) {
            ConnectionType.FTPS -> ftpRepository.deleteFile(connection, path)
            ConnectionType.FTP -> Result.failure(Exception("FTP classique est désactivé. Utilisez FTPS sécurisé."))
            ConnectionType.SMB -> smbRepository.deleteFile(connection, path)
            else -> Result.failure(Exception("Suppression non supportée pour ce type de connexion"))
        }
    }
    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File): Result<java.io.File> {
        return when (connection.type) {
            ConnectionType.SMB -> smbRepository.downloadToCache(connection, path, destination)
            else -> Result.failure(Exception("Ouverture directe non disponible pour ce type de connexion"))
        }
    }
    suspend fun download(connection: RemoteConnection, path: String, output: java.io.OutputStream,
        expectedSize: Long, checkCancelled: () -> Unit, progress: (Long) -> Unit) {
        when (connection.type) {
            ConnectionType.FTPS -> ftpRepository.download(connection, path, output, expectedSize, checkCancelled, progress)
            ConnectionType.FTP -> error("FTP classique est désactivé. Utilisez FTPS sécurisé.")
            ConnectionType.SMB -> smbRepository.download(connection, path, output, expectedSize, checkCancelled, progress).getOrThrow()
            else -> error("Téléchargement non disponible pour ce protocole")
        }
    }

}
