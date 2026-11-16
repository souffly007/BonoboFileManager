package fr.bonobo.filemanager.data.repository

import android.webkit.MimeTypeMap
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.SmbOp
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB client. Every public operation goes through [smbOperation]: one connection, one journal START line and
 * one end line (storage=SMB smbOp= durationMs= ntStatus=), and failures returned as a short French
 * message whose raw NTSTATUS stays in the technical detail. Path rules live in [SmbPaths].
 */
@Singleton
class SmbClientRepository @Inject constructor() {

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> =
        smbOperation(connection, SmbOp.LIST, Kind.DIRECTORY) { session ->
            val location = locate(connection, path)
            session.connectShare(location.share).use { opened ->
                val share = opened as? DiskShare ?: return@use emptyList()
                share.list(location.relative).mapNotNull { fileId ->
                    val fileName = fileId.fileName
                    if (fileName == "." || fileName == "..") return@mapNotNull null
                    val isDir = (fileId.fileAttributes and 0x10L) != 0L
                    FileItem(
                        path = SmbPaths.childVirtualPath(path, fileName),
                        name = fileName,
                        size = fileId.endOfFile,
                        lastModified = fileId.changeTime.toDate(),
                        isDirectory = isDir,
                        mimeType = if (isDir) null else MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                            ?: "application/octet-stream"
                    )
                }
            }
        }

    /** Deletes a file or a folder (recursively), then checks that it is really gone. */
    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> =
        smbOperation(connection, SmbOp.DELETE) { session ->
            val location = locate(connection, path)
            require(SmbPaths.segments(path).isNotEmpty() && location.relative.isNotBlank()) {
                "Suppression de la racine du partage refusée : rien n’a été supprimé."
            }
            session.connectShare(location.share).use { opened ->
                val share = opened as? DiskShare
                    ?: error("Ce partage SMB n’est pas un dossier de fichiers : rien n’a été supprimé.")
                val relative = location.relative
                // rm on a missing element lets the server answer STATUS_OBJECT_NAME_NOT_FOUND (NOT_FOUND).
                if (share.folderExists(relative)) share.rmdir(relative, true) else share.rm(relative)
                check(!share.fileExists(relative) && !share.folderExists(relative)) {
                    "Suppression non confirmée : l’élément est toujours présent sur le serveur."
                }
            }
        }

    suspend fun copyOrMove(
        connection: RemoteConnection,
        sourcePath: String,
        destinationDirectory: String,
        move: Boolean,
        duplicate: Boolean = false
    ): Result<Unit> {
        val op = when {
            duplicate -> SmbOp.DUPLICATE
            move -> SmbOp.MOVE
            else -> SmbOp.COPY
        }
        return smbOperation(connection, op) { session ->
            val source = locate(connection, sourcePath)
            val destination = locate(connection, destinationDirectory)
            require(destination.share.equals(source.share, ignoreCase = true)) { "Destination sur un autre partage : opération refusée" }
            disk(session, source.share) { share ->
                val target = SmbPaths.planTarget(source.relative, destination.relative, duplicate) {
                    share.fileExists(it) || share.folderExists(it)
                }
                val access = integrityAccess(share)
                IntegrityVerifier.copyThenDelete(access, source.relative, access, target,
                    copy = { copyEntry(share, source.relative, target) }, delete = {
                        if (move) {
                            currentCoroutineContext().ensureActive()
                            if (share.folderExists(source.relative)) share.rmdir(source.relative, true) else share.rm(source.relative)
                        }
                    }, deleting = move)
            }
        }
    }

    /** Metadata inspection uses the same reparse-point refusal as content verification. */
    suspend fun inspectTransfer(connection: RemoteConnection, path: String): Result<fr.bonobo.filemanager.domain.transfer.Inventory> =
        smbOperation(connection, SmbOp.INSPECT) { session ->
            val location = locate(connection, path)
            disk(session, location.share) { share ->
                fr.bonobo.filemanager.domain.transfer.TransferInventory.inspect(integrityAccess(share), location.relative) { node ->
                    share.getFileInformation(node).standardInformation.endOfFile.takeIf { it >= 0 }
                }
            }
        }

    suspend fun deleteVerifiedFile(connection: RemoteConnection, path: String, expected: String): Result<Unit> =
        smbOperation(connection, SmbOp.DELETE_SOURCE, Kind.FILE) { session ->
            val location = locate(connection, path)
            disk(session, location.share) { share ->
                val source = location.relative
                require(!share.folderExists(source)) { "Source SMB remplacée : suppression refusée" }
                check(IntegrityVerifier.fingerprint(integrityAccess(share), source) == expected) { "Source SMB modifiée : suppression refusée" }
                currentCoroutineContext().ensureActive()
                TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                currentCoroutineContext().ensureActive()
                share.rm(source)
            }
        }

    /** Reads a remote file into [destination] (opening or thumbnail). */
    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File,
        thumbnail: Boolean = false): Result<java.io.File> =
        smbOperation(connection, if (thumbnail) SmbOp.THUMBNAIL else SmbOp.OPEN, Kind.FILE) { session ->
            val location = locate(connection, path)
            disk(session, location.share) { share ->
                destination.parentFile?.mkdirs()
                openForRead(share, location.relative).use { remoteFile ->
                    FileOutputStream(destination).use { output ->
                        remoteFile.inputStream.use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
                    }
                }
            }
            destination
        }

    suspend fun uploadFromCache(connection: RemoteConnection, path: String, source: java.io.File): Result<Unit> =
        smbOperation(connection, SmbOp.SAVE, Kind.FILE) { session ->
            val location = locate(connection, path)
            disk(session, location.share) { share ->
                share.openFile(
                    location.relative,
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

    suspend fun download(connection: RemoteConnection, path: String, output: OutputStream, expectedSize: Long,
        checkCancelled: () -> Unit, progress: (Long) -> Unit): Result<Unit> =
        smbOperation(connection, SmbOp.DOWNLOAD, Kind.FILE) { session ->
            val location = locate(connection, path)
            disk(session, location.share) { share ->
                openForRead(share, location.relative).use { remoteFile ->
                    remoteFile.inputStream.use { input ->
                        fr.bonobo.filemanager.util.DownloadStream.copy(input, output, expectedSize, checkCancelled, progress)
                    }
                }
            }
        }

    // ---------------------------------------------------------------------------------------------

    /**
     * One SMB operation: connect, authenticate, run [block], close. Journal: START, then OK / CANCELLED /
     * classified failure with duration and NTSTATUS. Never logs host, share, path, user or password.
     */
    private suspend fun <T> smbOperation(connection: RemoteConnection, op: SmbOp, kind: Kind = Kind.UNKNOWN,
        block: suspend (Session) -> T): Result<T> = withContext(Dispatchers.IO) {
        val operation = Diagnostic.operation()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        Diagnostic.smb(op, TechnicalJournal.Result.START, operation, kind)
        val client = SMBClient()
        try {
            val value = client.connect(connection.host, port(connection)).use { connected ->
                block(connected.authenticate(authContext(connection)))
            }
            Diagnostic.smb(op, TechnicalJournal.Result.OK, operation, kind, elapsed())
            Result.success(value)
        } catch (e: CancellationException) {
            Diagnostic.smb(op, TechnicalJournal.Result.CANCELLED, operation, kind, elapsed())
            throw e
        } catch (e: Exception) {
            val failure = SmbErrors.classify(e)
            Diagnostic.smb(op, failure.journalResult, operation, kind, elapsed(), failure.ntStatus)
            Result.failure(failure.toThrowable(e))
        } finally {
            runCatching { client.close() }
        }
    }

    private inline fun <T> disk(session: Session, shareName: String, block: (DiskShare) -> T): T =
        session.connectShare(shareName).use { opened ->
            val share = opened as? DiskShare ?: error("Ce partage SMB n’est pas un dossier de fichiers.")
            block(share)
        }

    private fun openForRead(share: DiskShare, relative: String) = share.openFile(
        relative,
        EnumSet.of(AccessMask.FILE_READ_DATA),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
    )

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
                    SmbPaths.join(source, child.fileName),
                    SmbPaths.join(target, child.fileName)
                )
            }
            return
        }

        openForRead(share, source).use { inputFile ->
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
                IntegrityVerifier.Child(it.fileName, SmbPaths.join(path, it.fileName))
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

    private fun port(connection: RemoteConnection) = if (connection.port == 21 || connection.port == 0) 445 else connection.port

    private fun authContext(connection: RemoteConnection): AuthenticationContext =
        if (connection.user.isNotBlank() && connection.user != "anonymous") {
            AuthenticationContext(connection.user, connection.pass.toCharArray(), null)
        } else {
            AuthenticationContext.anonymous()
        }

    private fun locate(connection: RemoteConnection, path: String): SmbPaths.Location =
        SmbPaths.locate(SmbPaths.configured(connection.share), path)

    companion object {
        /** Share + starting folder of the « Partage » field (see [SmbPaths.parseShare]); invalid value → error. */
        fun configuredParts(connection: RemoteConnection): Pair<String?, String> {
            val configured = SmbPaths.configured(connection.share)
            return configured.share to configured.base
        }

        fun shareName(connection: RemoteConnection): String? = configuredParts(connection).first

        fun startPath(connection: RemoteConnection): String {
            val (_, base) = configuredParts(connection)
            return if (base.isBlank()) "/" else "/$base"
        }
    }
}
