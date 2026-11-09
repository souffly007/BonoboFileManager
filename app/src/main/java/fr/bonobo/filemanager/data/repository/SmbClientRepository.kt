package fr.bonobo.filemanager.data.repository

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.msdtyp.AccessMask
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import android.webkit.MimeTypeMap
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import fr.bonobo.filemanager.util.IntegrityVerifier
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
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
                
                val effectiveShare = smbShare(connection, path)
                
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
                
                val effectiveShare = smbShare(connection, path) ?: throw Exception("Partage non trouvé")
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

    suspend fun copyOrMove(
        connection: RemoteConnection,
        sourcePath: String,
        destinationDirectory: String,
        move: Boolean,
        duplicate: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = smbShare(connection, sourcePath) ?: throw Exception("Partage SMB introuvable")
                val source = getRelativePath(connection, sourcePath)
                val destination = getRelativePath(connection, destinationDirectory)
                val name = source.substringAfterLast('/').substringAfterLast('\\')
                require(name.isNotBlank()) { "Élément source invalide" }
                val requestedTarget = joinRemotePath(destination, name)
                val target = requestedTarget
                val sourceNorm = source.replace('/', '\\').trimEnd('\\').lowercase(java.util.Locale.ROOT)
                val targetNorm = target.replace('/', '\\').trimEnd('\\').lowercase(java.util.Locale.ROOT)
                require(sourceNorm != targetNorm) { "La source et la destination sont identiques" }
                require(!targetNorm.startsWith(sourceNorm + "\\")) {
                    "Impossible de copier ou déplacer un dossier à l’intérieur de lui-même"
                }

                session.connectShare(effectiveShare).use { share ->
                    if (share !is DiskShare) throw Exception("Le partage SMB n'est pas un disque")
                    val finalTarget = if (duplicate) uniqueTarget(share, requestedTarget) else target
                    if (!duplicate && (share.fileExists(target) || share.folderExists(target))) {
                        throw Exception("« $name » existe déjà dans le dossier de destination")
                    }
                    require(smbShare(connection, destinationDirectory) == effectiveShare) { "Destination sur un autre partage : opération refusée" }
                    val access = integrityAccess(share)
                    IntegrityVerifier.copyThenDelete(access, source, access, finalTarget,
                        copy = { copyEntry(share, source, finalTarget) }, delete = {
                            if (move) {
                                currentCoroutineContext().ensureActive()
                                if (share.folderExists(source)) share.rmdir(source, true) else share.rm(source)
                            }
                        }, deleting = move)
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        } finally { client.close() }
    }

    /** Metadata inspection uses the same reparse-point refusal as content verification. */
    suspend fun inspectTransfer(connection: RemoteConnection, path: String): Result<fr.bonobo.filemanager.domain.transfer.Inventory> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            runCatching {
                val port = if (connection.port in listOf(0, 21)) 445 else connection.port
                client.connect(connection.host, port).use { connected ->
                    val session = connected.authenticate(getAuthContext(connection))
                    session.connectShare(smbShare(connection, path) ?: error("Partage SMB introuvable")).use { opened ->
                        val share = opened as? DiskShare ?: error("Partage SMB non disque")
                        fr.bonobo.filemanager.domain.transfer.TransferInventory.inspect(integrityAccess(share),
                            getRelativePath(connection, path)) { node ->
                            share.getFileInformation(node).standardInformation.endOfFile.takeIf { it >= 0 }
                        }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        } finally { client.close() }
    }

    suspend fun deleteVerifiedFile(connection: RemoteConnection, path: String, expected: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            runCatching {
                val port = if (connection.port in listOf(0, 21)) 445 else connection.port
                client.connect(connection.host, port).use { connected ->
                    val session = connected.authenticate(getAuthContext(connection))
                    session.connectShare(smbShare(connection, path) ?: error("Partage SMB introuvable")).use { opened ->
                        val share = opened as? DiskShare ?: error("Partage SMB non disque")
                        val source = getRelativePath(connection, path)
                        require(!share.folderExists(source)) { "Source SMB remplacée : suppression refusée" }
                        check(IntegrityVerifier.fingerprint(integrityAccess(share), source) == expected) { "Source SMB modifiée : suppression refusée" }
                        currentCoroutineContext().ensureActive()
                        TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                        currentCoroutineContext().ensureActive()
                        share.rm(source)
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        } finally { client.close() }
    }

    private fun uniqueTarget(share: DiskShare?, requested: String): String {
        if (share == null) return requested
        if (!share.fileExists(requested) && !share.folderExists(requested)) return requested
        val normalized = requested.replace('/', '\\')
        val parent = normalized.substringBeforeLast('\\', "")
        val name = normalized.substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidateName = "$stem ($index)$extension"
            val candidate = if (parent.isBlank()) candidateName else "$parent\\$candidateName"
            if (!share.fileExists(candidate) && !share.folderExists(candidate)) return candidate
            index++
        }
    }

    private suspend fun copyEntry(share: DiskShare, source: String, target: String) {
        val operationContext = currentCoroutineContext()
        val observer = TransferEvents.observer()
        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, source.replace('/', '\\').substringAfterLast('\\')))
        operationContext.ensureActive()
        if (share.folderExists(source)) {
            share.mkdir(target)
            share.list(source).forEach { child ->
                if (child.fileName == "." || child.fileName == "..") return@forEach
                copyEntry(
                    share,
                    joinRemotePath(source, child.fileName),
                    joinRemotePath(target, child.fileName)
                )
            }
            return
        }

        share.openFile(
            source,
            EnumSet.of(AccessMask.FILE_READ_DATA),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { inputFile ->
            share.openFile(
                target,
                EnumSet.of(AccessMask.FILE_WRITE_DATA),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { outputFile ->
                inputFile.inputStream.use { input: InputStream ->
                    outputFile.outputStream.use { output: OutputStream ->
                        val size = share.getFileInformation(source).standardInformation.endOfFile
                        var previous = 0L
                        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, source.replace('/', '\\').substringAfterLast('\\'), fileBytes = 0, fileTotal = size, fileStart = true))
                        fr.bonobo.filemanager.util.DownloadStream.copy(input, output, size,
                            checkCancelled = { operationContext.ensureActive() }, progress = { bytes ->
                                observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = bytes - previous, fileBytes = bytes))
                                previous = bytes
                            })
                    }
                }
            }
        }
    }

    private fun integrityAccess(share: DiskShare) = object : IntegrityVerifier.Access {
        override fun identity(path: String) = path.replace('/', '\\').lowercase(java.util.Locale.ROOT)
        override fun children(path: String): List<IntegrityVerifier.Child>? {
            require(share.getFileInformation(path).basicInformation.fileAttributes and 0x400L == 0L) { "Point de réanalyse SMB refusé" }
            if (!share.folderExists(path)) { check(share.fileExists(path)) { "Document SMB inaccessible" }; return null }
            return share.list(path).filter { it.fileName !in listOf(".", "..") }.map {
                require(it.fileAttributes and 0x400L == 0L) { "Point de réanalyse SMB refusé" }
                IntegrityVerifier.Child(it.fileName, joinRemotePath(path, it.fileName))
            }
        }
        override fun open(path: String): InputStream {
            val handle = share.openFile(path, EnumSet.of(AccessMask.FILE_READ_DATA), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            try {
                return object : java.io.FilterInputStream(handle.inputStream) {
                    override fun close() { try { super.close() } finally { handle.close() } }
                }
            } catch (error: Exception) { handle.close(); throw error }
        }
    }

    private fun joinRemotePath(parent: String, child: String): String =
        if (parent.isBlank()) child else "${parent.trimEnd('/', '\\')}\\$child"

    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File): Result<java.io.File> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = smbShare(connection, path)
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


    suspend fun uploadFromCache(connection: RemoteConnection, path: String, source: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = smbShare(connection, path)
                    ?: throw Exception("Partage SMB introuvable")
                val relativePath = getRelativePath(connection, path)
                session.connectShare(effectiveShare).use { share ->
                    if (share !is DiskShare) throw Exception("Le partage SMB n'est pas un disque")
                    share.openFile(
                        relativePath,
                        EnumSet.of(AccessMask.FILE_WRITE_DATA),
                        null,
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    ).use { remoteFile ->
                        source.inputStream().use { input ->
                            remoteFile.outputStream.use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
                        }
                    }
                }
            }
            Unit
        }.also { client.close() }
    }

    suspend fun download(connection: RemoteConnection, path: String, output: java.io.OutputStream, expectedSize: Long, checkCancelled: () -> Unit, progress: (Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        runCatching {
            val port = if (connection.port == 21 || connection.port == 0) 445 else connection.port
            client.connect(connection.host, port).use { connectionInstance ->
                val session = connectionInstance.authenticate(getAuthContext(connection))
                val effectiveShare = smbShare(connection, path)
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

    /**
     * SMB accepts either a share ("Documents") or a share + starting folder
     * ("c/Users/Nico/Desktop").  The latter lets Bonobo expose only the folder
     * the user wants without pretending that a SMB sub-folder is a share.
     * Paths used by the UI stay virtual ("/", "/Photos", ...).
     */
    private fun smbShare(connection: RemoteConnection, path: String): String? {
        val (configuredShare, _) = configuredParts(connection)
        return configuredShare ?: path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun getRelativePath(connection: RemoteConnection, path: String): String {
        val (configuredShare, base) = configuredParts(connection)
        val virtual = if (configuredShare != null) path.trim('/')
        else path.trim('/').split("/").drop(1).joinToString("/")
        return listOf(base, virtual).filter { it.isNotBlank() }.joinToString("\\").replace('/', '\\')
    }

    companion object {
        fun configuredParts(connection: RemoteConnection): Pair<String?, String> {
            val configured = connection.share?.trim()?.replace('\\', '/')?.trim('/') ?: return null to ""
            if (configured.isBlank()) return null to ""
            val share = configured.substringBefore('/')
            val base = configured.substringAfter('/', "").trim('/')
            return share to base
        }

        fun shareName(connection: RemoteConnection): String? {
            val (configuredShare, _) = configuredParts(connection)
            return configuredShare
        }

        fun startPath(connection: RemoteConnection): String {
            val (_, base) = configuredParts(connection)
            return if (base.isBlank()) "/" else "/$base"
        }
    }
}
