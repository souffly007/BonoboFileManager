package fr.bonobo.filemanager.data.repository

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** Client SFTP/SSH. Les clés hôte sont mémorisées au premier contact (TOFU) puis contrôlées. */
@Singleton
class SftpClientRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("sftp_known_hosts", Context.MODE_PRIVATE)

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(connection) { sftp ->
                val remotePath = normalize(path)
                sftp.ls(remotePath).filter { it.name != "." && it.name != ".." }.map { entry ->
                    val fullPath = join(remotePath, entry.name)
                    val directory = entry.attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
                    FileItem(
                        path = fullPath,
                        name = entry.name,
                        size = if (directory) 0L else entry.attributes.size,
                        lastModified = Date(entry.attributes.mtime * 1000L),
                        isDirectory = directory,
                        mimeType = if (directory) null else MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                    )
                }
            }
        }
    }

    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(connection) { sftp ->
                val attrs = sftp.stat(path)
                if (attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) removeTree(sftp, path)
                else sftp.rm(path)
            }
        }
    }

    suspend fun deleteVerifiedFile(connection: RemoteConnection, path: String, expected: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            currentCoroutineContext().ensureActive()
            withSftp(connection) { sftp ->
                require(sftp.lstat(path).type == net.schmizz.sshj.sftp.FileMode.Type.REGULAR) { "Source SFTP remplacée ou non régulière : suppression refusée" }
                sftp.open(path).use { remote ->
                    val input = remote.RemoteFileInputStream()
                    val access = object : fr.bonobo.filemanager.util.IntegrityVerifier.Access {
                        override fun children(path: String): List<fr.bonobo.filemanager.util.IntegrityVerifier.Child>? = null
                        override fun open(path: String) = input
                    }
                    check(fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(access, path) == expected) { "Source SFTP modifiée : suppression refusée" }
                }
                currentCoroutineContext().ensureActive()
                sftp.rm(path)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    suspend fun downloadToFile(connection: RemoteConnection, path: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            withSftp(connection) { sftp -> sftp.get(path, destination.absolutePath) }
            destination
        }
    }

    suspend fun uploadFromFile(connection: RemoteConnection, path: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { withSftp(connection) { sftp -> sftp.put(source.absolutePath, path) } }
    }

    suspend fun download(
        connection: RemoteConnection,
        path: String,
        output: OutputStream,
        expectedSize: Long,
        checkCancelled: () -> Unit,
        progress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        checkCancelled(); operationContext.ensureActive()
        withSftp(connection) { sftp ->
            val attributes = sftp.lstat(path)
            require(attributes.type == net.schmizz.sshj.sftp.FileMode.Type.REGULAR) { "Lien ou source SFTP non régulière refusé" }
            require(expectedSize < 0 || attributes.size == expectedSize) { "Taille SFTP modifiée" }
            sftp.open(path).use { remote ->
                remote.RemoteFileInputStream().use { input ->
                    fr.bonobo.filemanager.util.DownloadStream.copy(input, output, attributes.size,
                        { operationContext.ensureActive(); checkCancelled() }, progress)
                }
            }
        }
    }

    private suspend fun <T> withSftp(connection: RemoteConnection, block: suspend (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuVerifier(connection.host, connection.port))
        try {
            ssh.connect(connection.host, if (connection.port <= 0) 22 else connection.port)
            ssh.authPassword(connection.user, connection.pass)
            ssh.newSFTPClient().use { sftp -> return block(sftp) }
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }

    private inner class TofuVerifier(private val host: String, private val port: Int) : HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            val fingerprint = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP)
            val prefKey = "$host:${if (this.port <= 0) 22 else this.port}"
            val known = prefs.getString(prefKey, null)
            if (known == null) {
                prefs.edit().putString(prefKey, fingerprint).apply()
                return true
            }
            return known == fingerprint
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
    }

    private fun removeTree(sftp: net.schmizz.sshj.sftp.SFTPClient, path: String) {
        sftp.ls(path).filter { it.name != "." && it.name != ".." }.forEach { child ->
            val childPath = join(path, child.name)
            if (child.attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) removeTree(sftp, childPath)
            else sftp.rm(childPath)
        }
        sftp.rmdir(path)
    }

    private fun normalize(path: String): String = if (path.isBlank()) "/" else if (path.startsWith('/')) path else "/$path"
    private fun join(parent: String, child: String): String = if (parent == "/") "/$child" else parent.trimEnd('/') + "/" + child
}
