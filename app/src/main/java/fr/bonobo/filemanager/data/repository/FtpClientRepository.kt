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
        val client = createClient(connection)
        
        runCatching {
            client.connect(connection.host, connection.port)
            check(client.login(connection.user, connection.pass)) { "Identifiants FTP refusés" }
            client.enterLocalPassiveMode()
            
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            
            val files = client.listFiles(path)
            check(org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) { "Lecture du dossier refusée (${client.replyCode})" }
            files.filter { it.name != "." && it.name != ".." }.map { ftpFile ->
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
        val client = createClient(connection)
        runCatching {
            client.connect(connection.host, connection.port)
            check(client.login(connection.user, connection.pass)) { "Identifiants FTP refusés" }
            
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

    suspend fun download(connection: RemoteConnection, path: String, output: java.io.OutputStream,
        expectedSize: Long, checkCancelled: () -> Unit, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val client = createClient(connection)
        try {
            checkCancelled()
            client.connect(connection.host, connection.port)
            check(client.login(connection.user, connection.pass)) { "Identifiants FTP refusés" }
            client.enterLocalPassiveMode()
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            transfer(client, path, output, expectedSize, checkCancelled, progress)
        } finally {
            cleanup(client)
        }
    }

    companion object {
        // Shared with real FTPS integration tests. Never report success without the final FTP reply.
        fun transfer(client: FTPClient, path: String, output: java.io.OutputStream, expectedSize: Long,
            checkCancelled: () -> Unit = {}, progress: (Long) -> Unit = {}) {
            check(client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)) { "Mode binaire FTP refusé" }
            val input = client.retrieveFileStream(path) ?: error("Le serveur refuse le téléchargement (${client.replyCode})")
            input.use { fr.bonobo.filemanager.util.DownloadStream.copy(it, output, expectedSize, checkCancelled, progress) }
            checkCancelled()
            check(client.completePendingCommand()) { "Le serveur n'a pas confirmé la fin du téléchargement (${client.replyCode})" }
        }
    }

    private fun createClient(connection: RemoteConnection): FTPClient {
        check(connection.type == ConnectionType.FTPS) { "FTP classique est désactivé. Utilisez FTPS sécurisé." }
        val client = FTPSClient().apply {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            setTrustManager(factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first())
            isEndpointCheckingEnabled = true
        }
        client.connectTimeout = 10_000
        client.defaultTimeout = 15_000
        client.setDataTimeout(java.time.Duration.ofSeconds(30))
        return client
    }

    private fun cleanup(client: FTPClient) {
        try {
            if (client.isConnected) {
                try { client.logout() } finally { client.disconnect() }
            }
        } catch (_: Exception) {}
    }
}
