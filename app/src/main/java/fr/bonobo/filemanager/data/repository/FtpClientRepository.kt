package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FtpClientRepository @Inject constructor() {

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        val client = if (connection.type == ConnectionType.FTPS) FTPSClient() else FTPClient()
        
        runCatching {
            client.connect(connection.host, connection.port)
            client.login(connection.user, connection.pass)
            client.enterLocalPassiveMode()
            
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            
            val files = client.listFiles(path)
            files.map { ftpFile ->
                FileItem(
                    path = if (path.endsWith("/")) "$path${ftpFile.name}" else "$path/${ftpFile.name}",
                    name = ftpFile.name,
                    size = ftpFile.size,
                    lastModified = ftpFile.timestamp?.time ?: Date(),
                    isDirectory = ftpFile.isDirectory,
                    mimeType = if (ftpFile.isDirectory) null else "application/octet-stream"
                )
            }
        }.also {
            cleanup(client)
        }
    }

    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = if (connection.type == ConnectionType.FTPS) FTPSClient() else FTPClient()
        runCatching {
            client.connect(connection.host, connection.port)
            client.login(connection.user, connection.pass)
            
            val deleted = client.deleteFile(path)
            if (!deleted) {
                // Essayer de supprimer comme répertoire si le fichier échoue
                if (!client.removeDirectory(path)) {
                    throw Exception("Impossible de supprimer l'élément distant")
                }
            }
        }.also {
            cleanup(client)
        }
    }

    private fun cleanup(client: FTPClient) {
        try {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        } catch (_: Exception) {}
    }
}
