package fr.bonobo.filemanager.data.repository

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbClientRepository @Inject constructor() {

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val authContext = getAuthContext(connection)
                val session = connectionInstance.authenticate(authContext)
                
                val effectiveShare = connection.share ?: path.trim('/').split("/").firstOrNull()
                
                if (effectiveShare == null || effectiveShare.isEmpty()) {
                    throw Exception("Veuillez spécifier un nom de partage (ex: public) pour SMB")
                } else {
                    val relativePath = getRelativePath(connection, path)
                    
                    session.connectShare(effectiveShare).use { share ->
                        if (share is DiskShare) {
                            val list = share.list(relativePath)
                            list.map { fileId ->
                                val fileName = fileId.fileName
                                if (fileName == "." || fileName == "..") return@map null
                                
                                val fullPath = if (path.endsWith("/")) "$path$fileName" else "$path/$fileName"
                                val isDir = (fileId.fileAttributes and 0x10L) != 0L
                                
                                FileItem(
                                    path = fullPath,
                                    name = fileName,
                                    size = fileId.endOfFile,
                                    lastModified = fileId.changeTime.toDate(),
                                    isDirectory = isDir,
                                    mimeType = if (isDir) null else "application/octet-stream"
                                )
                            }.filterNotNull()
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }.onFailure { e ->
            e.printStackTrace()
            client.close()
        }
    }

    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val authContext = getAuthContext(connection)
                val session = connectionInstance.authenticate(authContext)
                
                val effectiveShare = connection.share ?: path.trim('/').split("/").firstOrNull() ?: throw Exception("Partage non trouvé")
                val relativePath = getRelativePath(connection, path)
                
                session.connectShare(effectiveShare).use { share ->
                    if (share is DiskShare) {
                        if (share.folderExists(relativePath)) {
                            share.rmdir(relativePath, true)
                        } else {
                            share.rm(relativePath)
                        }
                    }
                }
            }
        }.onFailure { 
            client.close()
        }
    }

    private fun getAuthContext(connection: RemoteConnection): AuthenticationContext {
        return if (connection.user.isNotBlank() && connection.user != "anonymous") {
            AuthenticationContext(connection.user, connection.pass.toCharArray(), null)
        } else {
            AuthenticationContext.anonymous()
        }
    }

    private fun getRelativePath(connection: RemoteConnection, path: String): String {
        return if (connection.share != null) {
            path.trim('/')
        } else {
            path.trim('/').split("/").drop(1).joinToString("/")
        }
    }
}
