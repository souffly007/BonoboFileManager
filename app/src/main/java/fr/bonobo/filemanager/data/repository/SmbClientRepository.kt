package fr.bonobo.filemanager.data.repository

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.msdtyp.AccessMask
import java.io.FileOutputStream
import java.util.EnumSet
import android.webkit.MimeTypeMap
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
                                    mimeType = if (isDir) null else MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                                        ?: "application/octet-stream"
                                )
                            }.filterNotNull()
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }.also { client.close() }
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
        }.also { client.close() }
    }

    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File): Result<java.io.File> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = connection.share ?: path.trim('/').split("/").firstOrNull()
                    ?: throw Exception("Partage SMB introuvable")
                val relativePath = getRelativePath(connection, path)
                session.connectShare(effectiveShare).use { share ->
                    if (share !is DiskShare) throw Exception("Le partage SMB n'est pas un disque")
                    val parent = destination.parentFile
                    if (parent != null) parent.mkdirs()
                    share.openFile(
                        relativePath,
                        EnumSet.of(AccessMask.FILE_READ_DATA),
                        null,
                        EnumSet.of(
                            SMB2ShareAccess.FILE_SHARE_READ,
                            SMB2ShareAccess.FILE_SHARE_WRITE,
                            SMB2ShareAccess.FILE_SHARE_DELETE
                        ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    ).use { remoteFile ->
                        FileOutputStream(destination).use { output ->
                            remoteFile.inputStream.use { input ->
                                input.copyTo(output, DEFAULT_BUFFER_SIZE * 16)
                            }
                        }
                    }
                }
            }
            destination
        }.also { client.close() }
    }

    suspend fun download(connection: RemoteConnection, path: String, output: java.io.OutputStream, expectedSize: Long, checkCancelled: () -> Unit, progress: (Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = connection.share ?: path.trim('/').split("/").firstOrNull()
                    ?: throw Exception("Partage SMB introuvable")
                val relativePath = getRelativePath(connection, path)
                session.connectShare(effectiveShare).use { share ->
                    if (share !is DiskShare) throw Exception("Le partage SMB n'est pas un disque")
                    share.openFile(
                        relativePath,
                        EnumSet.of(AccessMask.FILE_READ_DATA),
                        null,
                        EnumSet.of(
                            SMB2ShareAccess.FILE_SHARE_READ,
                            SMB2ShareAccess.FILE_SHARE_WRITE,
                            SMB2ShareAccess.FILE_SHARE_DELETE
                        ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    ).use { remoteFile ->
                        remoteFile.inputStream.use { input ->
                            fr.bonobo.filemanager.util.DownloadStream.copy(input, output, expectedSize, checkCancelled, progress)
                        }
                    }
                }
            }
            Unit
        }.also { client.close() }
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
