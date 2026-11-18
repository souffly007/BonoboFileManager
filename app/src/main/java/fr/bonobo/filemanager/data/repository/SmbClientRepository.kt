package fr.bonobo.filemanager.data.repository

import android.content.Context
import android.webkit.MimeTypeMap
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.SmbOp
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.domain.transfer.ConflictAction
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.domain.transfer.TransferSkipped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB client.
 *  - Sessions: one per configured connection (host, port, share, user), shared by listings, thumbnails
 *    and transfers ([SessionPool]); reconnected when dropped, closed after [IDLE_MS] of inactivity and
 *    when the explorer is left ([release]); at most [MAX_CONCURRENT] operations at once per session.
 *  - Writes (copy, move, duplicate, text save) go through [SmbTransfers]: « .bonobo-part », SHA-256,
 *    server rename, recorded receipts and verified cleanup.
 *  - Every public operation logs START and its end (storage=SMB smbOp= durationMs= ntStatus=), never a
 *    host, share, path, user or password; failures come back as a short French message whose raw
 *    NTSTATUS stays in the technical detail.
 */
@Singleton
class SmbClientRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val transfers = SmbTransfers(java.io.File(context.filesDir, RECEIPTS_DIRECTORY))

    /** In-memory identity of a session. [secret] is a hash of the password; [toString] never shows it. */
    private data class Key(val host: String, val port: Int, val share: String, val user: String,
        val secret: String, val anonymous: Boolean) {
        override fun toString() = "Key(smb)"
    }

    /** One SMB connection + authenticated session, with its connected shares. */
    private class Link(val client: SMBClient, val connection: Connection, val session: Session) {
        private val shares = HashMap<String, DiskShare>()
        fun alive(): Boolean = connection.isConnected
        fun disk(name: String): DiskShare = synchronized(shares) {
            val key = name.lowercase(java.util.Locale.ROOT)
            shares[key]?.takeIf { it.isConnected }?.let { return it }
            shares.remove(key)?.let { runCatching { it.close() } }
            val opened = session.connectShare(name)
            val disk = opened as? DiskShare
            if (disk == null) {
                runCatching { opened.close() }
                throw IllegalStateException(SmbErrors.NOT_A_DISK)
            }
            shares[key] = disk
            disk
        }
        fun close() {
            synchronized(shares) { shares.values.forEach { runCatching { it.close() } }; shares.clear() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private val sessions = SessionPool<Key, Link>(
        open = { key -> openLink(key) },
        alive = { it.alive() },
        close = { link ->
            link.close()
            Diagnostic.smb(SmbOp.DISCONNECT, TechnicalJournal.Result.OK)
        },
        broken = SmbErrors::isDisconnect,
        idleMillis = IDLE_MS,
        maxConcurrent = MAX_CONCURRENT
    )

    /** Credentials of a key, only kept while a session for it is being opened. */
    private val pendingCredentials = java.util.concurrent.ConcurrentHashMap<Key, RemoteConnection>()

    init {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "smb-session-sweeper").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { sessions.sweep() } }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
    }

    // ─────────────────────────────── Operations ───────────────────────────────

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> =
        smbOperation(connection, SmbOp.LIST, Kind.DIRECTORY, retry = true) { link ->
            val location = locate(connection, path)
            link.disk(location.share).list(location.relative).mapNotNull { fileId ->
                val fileName = fileId.fileName
                if (fileName == "." || fileName == "..") return@mapNotNull null
                val isDir = (fileId.fileAttributes and ATTR_DIRECTORY) != 0L
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

    /** Deletes a file or a folder (recursively), then checks that it is really gone. */
    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> =
        smbOperation(connection, SmbOp.DELETE) { link ->
            val location = locate(connection, path)
            require(SmbPaths.segments(path).isNotEmpty() && location.relative.isNotBlank()) {
                "Suppression de la racine du partage refusée : rien n’a été supprimé."
            }
            val disk = link.disk(location.share)
            val relative = location.relative
            // rm on a missing element lets the server answer STATUS_OBJECT_NAME_NOT_FOUND (NOT_FOUND).
            if (disk.folderExists(relative)) disk.rmdir(relative, true) else disk.rm(relative)
            check(!disk.fileExists(relative) && !disk.folderExists(relative)) {
                "Suppression non confirmée : l’élément est toujours présent sur le serveur."
            }
        }

    /** Copy / move / duplicate on the same share, through « .bonobo-part » + SHA-256 + rename. */
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
        return smbOperation(connection, op) { link ->
            val source = locate(connection, sourcePath)
            val destination = locate(connection, destinationDirectory)
            require(destination.share.equals(source.share, ignoreCase = true)) { "Destination sur un autre partage : opération refusée" }
            val disk = ShareDisk(link.disk(source.share))
            val target = SmbPaths.planTarget(source.relative, destination.relative, duplicate) { disk.stat(it) != null }
            transfers.copy(disk, SmbTransfers.volume(connection.id, source.share), source.relative, target, move)
        }
    }

    /** Metadata inspection uses the same reparse-point refusal as content verification. */
    suspend fun inspectTransfer(connection: RemoteConnection, path: String): Result<fr.bonobo.filemanager.domain.transfer.Inventory> =
        smbOperation(connection, SmbOp.INSPECT, retry = true) { link ->
            val location = locate(connection, path)
            val disk = ShareDisk(link.disk(location.share))
            fr.bonobo.filemanager.domain.transfer.TransferInventory.inspect(disk.integrityAccess(), location.relative) { node ->
                disk.stat(node)?.size?.takeIf { it >= 0 }
            }
        }

    /**
     * Move to the phone: deletes the source only if its size and change time are those read before the
     * download (no second download to re-hash it).
     */
    suspend fun deleteIfUnchanged(connection: RemoteConnection, path: String, size: Long, changed: Long): Result<Unit> =
        smbOperation(connection, SmbOp.DELETE_SOURCE, Kind.FILE) { link ->
            val location = locate(connection, path)
            val disk = ShareDisk(link.disk(location.share))
            val now = disk.stat(location.relative) ?: error("Source SMB disparue : rien n’a été supprimé")
            require(!now.directory) { "Source SMB remplacée par un dossier : suppression refusée" }
            check(now.size == size && now.changed == changed) { "Source SMB modifiée pendant la copie : la copie est conservée, la source n’est pas supprimée" }
            fr.bonobo.filemanager.domain.transfer.TransferEvents.emit(
                fr.bonobo.filemanager.domain.transfer.TransferEvents.Event(fr.bonobo.filemanager.domain.transfer.TransferPhase.DELETING))
            disk.deleteFile(location.relative)
            check(disk.stat(location.relative) == null) { "Suppression de la source non confirmée : elle est toujours sur le serveur" }
        }

    /**
     * Reads a remote file into [destination] (opening or thumbnail). [maxBytes]: refuse a larger file
     * and never read more than that.
     */
    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File,
        thumbnail: Boolean = false, maxBytes: Long? = null): Result<java.io.File> =
        smbOperation(connection, if (thumbnail) SmbOp.THUMBNAIL else SmbOp.OPEN, Kind.FILE, retry = true) { link ->
            val location = locate(connection, path)
            val disk = ShareDisk(link.disk(location.share))
            if (maxBytes != null) {
                val size = disk.stat(location.relative)?.size ?: error("Fichier introuvable sur le serveur")
                check(size <= maxBytes) { "Fichier trop volumineux pour une miniature" }
            }
            destination.parentFile?.mkdirs()
            disk.openRead(location.relative).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (maxBytes != null) check(total <= maxBytes) { "Fichier trop volumineux pour une miniature" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            destination
        }

    /** Saves an edited file: verified « .bonobo-part », then server rename over the original (never written directly). */
    suspend fun uploadFromCache(connection: RemoteConnection, path: String, source: java.io.File): Result<Unit> =
        smbOperation(connection, SmbOp.SAVE, Kind.FILE) { link ->
            val location = locate(connection, path)
            require(location.relative.isNotBlank()) { "Chemin SMB invalide" }
            val disk = ShareDisk(link.disk(location.share))
            transfers.replace(disk, SmbTransfers.volume(connection.id, location.share), location.relative, source)
        }

    suspend fun download(connection: RemoteConnection, path: String, output: OutputStream, expectedSize: Long,
        checkCancelled: () -> Unit, progress: (Long) -> Unit): Result<Unit> =
        smbOperation(connection, SmbOp.DOWNLOAD, Kind.FILE) { link ->
            val location = locate(connection, path)
            ShareDisk(link.disk(location.share)).openRead(location.relative).use { input ->
                fr.bonobo.filemanager.util.DownloadStream.copy(input, output, expectedSize, checkCancelled, progress)
            }
        }

    /**
     * Removes the partial files of [connection] left by an earlier failure or by a killed app (deferred
     * receipts), share by share. Silent: what cannot be removed now is retried later.
     */
    suspend fun cleanPending(connection: RemoteConnection) {
        if (connection.id == 0L) return
        transfers.adoptInterrupted()
        val volumes = transfers.pendingVolumes().filter { SmbTransfers.parseVolume(it)?.first == connection.id }
        for (volume in volumes) {
            val share = SmbTransfers.parseVolume(volume)?.second ?: continue
            smbOperation(connection, SmbOp.CLEANUP) { link ->
                transfers.cleanDeferred(volume, ShareDisk(link.disk(share)))
            }
        }
    }

    // ─────────────────────────────── Phone → server, new folder, rename ───────────────────────

    /** Phone → server through « .bonobo-part » + SHA-256 + rename, with the USB conflict rules. */
    suspend fun upload(connection: RemoteConnection, source: SmbTransfers.UploadSource, sourcePath: String, name: String,
        destinationDirectory: String, conflict: ConflictAction, compareHash: Boolean, moving: Boolean): Result<SmbTransfers.UploadResult> =
        smbOperation(connection, SmbOp.UPLOAD) { link ->
            val location = locate(connection, destinationDirectory)
            val disk = ShareDisk(link.disk(location.share))
            transfers.upload(disk, SmbTransfers.volume(connection.id, location.share), source, sourcePath, name,
                location.relative, conflict, compareHash, moving)
        }

    /** Free bytes on the share of [path] for this user; null when the server does not say. */
    suspend fun freeSpace(connection: RemoteConnection, path: String): Long? =
        smbOperation(connection, SmbOp.SPACE, retry = true) { link ->
            ShareDisk(link.disk(locate(connection, path).share)).freeSpace()
        }.getOrNull()

    suspend fun createFolder(connection: RemoteConnection, parentPath: String, name: String): Result<String> =
        smbOperation(connection, SmbOp.MKDIR, Kind.DIRECTORY) { link ->
            val location = locate(connection, parentPath)
            transfers.createFolder(ShareDisk(link.disk(location.share)), location.relative, name)
            SmbPaths.childVirtualPath(parentPath, name)
        }

    /** Renames [path] in its folder; returns the new virtual path. */
    suspend fun rename(connection: RemoteConnection, path: String, newName: String): Result<String> =
        smbOperation(connection, SmbOp.RENAME) { link ->
            val location = locate(connection, path)
            require(SmbPaths.segments(path).isNotEmpty() && location.relative.isNotBlank()) { "La racine du partage ne peut pas être renommée." }
            transfers.renameEntry(ShareDisk(link.disk(location.share)), location.relative, newName)
            val parent = path.trimEnd('/').substringBeforeLast('/', "")
            SmbPaths.childVirtualPath(parent, newName)
        }

    // ─────────────────────────────── Server → phone (folders) ───────────────────────────────

    /** Metadata of [path] and its descendants (relative paths, « \\ » separated); used before and after a download. */
    suspend fun snapshot(connection: RemoteConnection, path: String): Result<Map<String, SmbTransfers.Meta>> =
        smbOperation(connection, SmbOp.INSPECT, retry = true) { link ->
            val location = locate(connection, path)
            transfers.snapshot(ShareDisk(link.disk(location.share)), location.relative)
        }

    /**
     * Downloads [path] (file or folder, as described by [snapshot]) to [target] on the phone, each file
     * hashed while it is written then re-read locally (SHA-256 must match). Returns relative path → SHA-256.
     */
    suspend fun downloadTree(connection: RemoteConnection, path: String, target: java.io.File,
        snapshot: Map<String, SmbTransfers.Meta>): Result<Map<String, String>> =
        smbOperation(connection, SmbOp.DOWNLOAD) { link ->
            val location = locate(connection, path)
            val disk = ShareDisk(link.disk(location.share))
            val observer = fr.bonobo.filemanager.domain.transfer.TransferEvents.observer()
            val context = kotlinx.coroutines.currentCoroutineContext()
            val hashes = linkedMapOf<String, String>()
            for ((relative, meta) in snapshot) {
                val local = if (relative.isEmpty()) target else relative.split('\\').fold(target) { dir, part -> fr.bonobo.filemanager.util.SafeFiles.child(dir, part) }
                if (meta.directory) { check(local.isDirectory || local.mkdirs()) { "Dossier temporaire impossible à créer" }; continue }
                local.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Dossier temporaire impossible à créer" } }
                val remote = if (relative.isEmpty()) location.relative else SmbPaths.join(location.relative, relative)
                observer?.invoke(fr.bonobo.filemanager.domain.transfer.TransferEvents.Event(fr.bonobo.filemanager.domain.transfer.TransferPhase.COPYING,
                    remote, local.name, fileBytes = 0, fileTotal = meta.size, fileStart = true))
                val digest = SmbTransfers.sha256()
                var previous = 0L
                java.security.DigestOutputStream(local.outputStream(), digest).use { output ->
                    disk.openRead(remote).use { input ->
                        fr.bonobo.filemanager.util.DownloadStream.copy(input, output, meta.size, { context.ensureActive() }) { bytes ->
                            observer?.invoke(fr.bonobo.filemanager.domain.transfer.TransferEvents.Event(
                                fr.bonobo.filemanager.domain.transfer.TransferPhase.COPYING, bytesDelta = bytes - previous, fileBytes = bytes))
                            previous = bytes
                        }
                    }
                }
                val streamed = SmbTransfers.hex(digest.digest())
                check(fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(fr.bonobo.filemanager.util.LocalIntegrityAccess, local.path) == streamed) {
                    "Copie temporaire différente des octets reçus : source conservée"
                }
                hashes[relative] = streamed
            }
            hashes
        }

    /** Move to the phone: deletes the server element only if its tree still matches [snapshot]. */
    suspend fun deleteTreeIfUnchanged(connection: RemoteConnection, path: String, snapshot: Map<String, SmbTransfers.Meta>): Result<Unit> =
        smbOperation(connection, SmbOp.DELETE_SOURCE) { link ->
            val location = locate(connection, path)
            require(SmbPaths.segments(path).isNotEmpty() && location.relative.isNotBlank()) { "Suppression de la racine du partage refusée." }
            val disk = ShareDisk(link.disk(location.share))
            check(transfers.snapshot(disk, location.relative) == snapshot) {
                "Source SMB modifiée pendant la copie : la copie est conservée, la source n’est pas supprimée"
            }
            fr.bonobo.filemanager.domain.transfer.TransferEvents.emit(
                fr.bonobo.filemanager.domain.transfer.TransferEvents.Event(fr.bonobo.filemanager.domain.transfer.TransferPhase.DELETING))
            if (snapshot[""]?.directory == true) disk.deleteTree(location.relative) else disk.deleteFile(location.relative)
            check(disk.stat(location.relative) == null) { "Suppression de la source non confirmée : elle est toujours sur le serveur" }
        }

    /** Connection ids that still have partial files to remove on their server. */
    fun pendingConnectionIds(): Set<Long> {
        transfers.adoptInterrupted()
        return transfers.pendingVolumes().mapNotNull { SmbTransfers.parseVolume(it)?.first }.toSet()
    }

    /** Leaving the explorer: closes the session now, or when its last operation (transfer) ends. */
    fun release(connection: RemoteConnection) = sessions.release(key(connection))

    // ─────────────────────────────── Session + journal ───────────────────────────────

    /**
     * One SMB operation on the shared session. Journal: START, then OK / CANCELLED / classified failure
     * with duration and NTSTATUS. [retry]: repeat once on a fresh session if the session was lost
     * (only for operations that can safely be repeated).
     */
    private suspend fun <T> smbOperation(connection: RemoteConnection, op: SmbOp, kind: Kind = Kind.UNKNOWN,
        retry: Boolean = false, block: suspend (Link) -> T): Result<T> = withContext(Dispatchers.IO) {
        val operation = Diagnostic.operation()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        Diagnostic.smb(op, TechnicalJournal.Result.START, operation, kind)
        val key = key(connection)
        pendingCredentials[key] = connection
        try {
            val value = sessions.use(key, retry) { link -> block(link) }
            Diagnostic.smb(op, TechnicalJournal.Result.OK, operation, kind, elapsed())
            Result.success(value)
        } catch (e: CancellationException) {
            Diagnostic.smb(op, TechnicalJournal.Result.CANCELLED, operation, kind, elapsed())
            throw e
        } catch (e: TransferSkipped) {
            // « Ignorer » / identical file: an expected outcome, kept as is for the transfer card.
            Diagnostic.smb(op, TechnicalJournal.Result.SKIPPED, operation, kind, elapsed())
            Result.failure(e)
        } catch (e: TransferFailure) {
            // Already typed (e.g. SPACE_CHECK): keep its kind for the transfer card.
            Diagnostic.smb(op, TechnicalJournal.Result.ERROR, operation, kind, elapsed())
            Result.failure(e)
        } catch (e: Exception) {
            val failure = SmbErrors.classify(e)
            Diagnostic.smb(op, failure.journalResult, operation, kind, elapsed(), failure.ntStatus)
            Result.failure(failure.toThrowable(e))
        }
    }

    private fun openLink(key: Key): Link {
        val connection = pendingCredentials[key] ?: error("Connexion SMB inconnue")
        val started = System.nanoTime()
        val client = SMBClient()
        try {
            val connected = client.connect(connection.host, key.port)
            try {
                val auth = if (key.anonymous) AuthenticationContext.anonymous()
                    else AuthenticationContext(connection.user, connection.pass.toCharArray(), null)
                val link = Link(client, connected, connected.authenticate(auth))
                Diagnostic.smb(SmbOp.CONNECT, TechnicalJournal.Result.OK, durationMs = (System.nanoTime() - started) / 1_000_000)
                return link
            } catch (e: Exception) { runCatching { connected.close() }; throw e }
        } catch (e: Exception) {
            runCatching { client.close() }
            val failure = SmbErrors.classify(e)
            Diagnostic.smb(SmbOp.CONNECT, failure.journalResult, durationMs = (System.nanoTime() - started) / 1_000_000,
                ntStatus = failure.ntStatus)
            throw e
        }
    }

    private fun key(connection: RemoteConnection): Key {
        val anonymous = connection.user.isBlank() || connection.user == "anonymous"
        val secret = java.security.MessageDigest.getInstance("SHA-256")
            .digest(connection.pass.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val share = runCatching { SmbPaths.configured(connection.share).share }.getOrNull().orEmpty()
        return Key(connection.host.trim().lowercase(java.util.Locale.ROOT), port(connection),
            share.lowercase(java.util.Locale.ROOT), connection.user, secret, anonymous)
    }

    private fun port(connection: RemoteConnection) = if (connection.port == 21 || connection.port == 0) 445 else connection.port

    private fun locate(connection: RemoteConnection, path: String): SmbPaths.Location =
        SmbPaths.locate(SmbPaths.configured(connection.share), path)

    // ─────────────────────────────── smbj → SmbDisk ───────────────────────────────

    private class ShareDisk(private val share: DiskShare) : SmbDisk {
        private val shareAll = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE)

        override fun stat(path: String): SmbNode? = try {
            val info = share.getFileInformation(path)
            val attributes = info.basicInformation.fileAttributes
            SmbNode(SmbTransfers.nameOf(path), (attributes and ATTR_DIRECTORY) != 0L, info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(), info.basicInformation.changeTime.toEpochMillis(),
                (attributes and ATTR_REPARSE) != 0L)
        } catch (e: SMBApiException) {
            if (absent(e)) null else throw e
        }

        override fun list(path: String): List<SmbNode> = share.list(path)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                SmbNode(it.fileName, (it.fileAttributes and ATTR_DIRECTORY) != 0L, it.endOfFile,
                    it.lastWriteTime.toEpochMillis(), it.changeTime.toEpochMillis(), (it.fileAttributes and ATTR_REPARSE) != 0L)
            }

        override fun openRead(path: String): InputStream {
            val file = share.openFile(path, EnumSet.of(AccessMask.FILE_READ_DATA), null, shareAll,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            return try {
                object : FilterInputStream(file.inputStream) {
                    override fun close() { try { super.close() } finally { file.close() } }
                }
            } catch (e: Exception) { file.close(); throw e }
        }

        override fun createNew(path: String): OutputStream {
            val file = share.openFile(path, EnumSet.of(AccessMask.FILE_WRITE_DATA), null, shareAll,
                SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            return try {
                object : FilterOutputStream(file.outputStream) {
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                    override fun close() { try { super.close() } finally { file.close() } }
                }
            } catch (e: Exception) { file.close(); throw e }
        }

        override fun mkdir(path: String) = share.mkdir(path)

        override fun rename(from: String, to: String, replace: Boolean) {
            val entry = share.open(from, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, shareAll,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java))
            try { entry.rename(to, replace) } finally { entry.close() }
        }

        override fun deleteFile(path: String) = share.rm(path)
        override fun deleteEmptyDirectory(path: String) = share.rmdir(path, false)
        override fun deleteTree(path: String) = share.rmdir(path, true)

        override fun freeSpace(): Long? = runCatching { share.shareInformation.callerFreeSpace }.getOrNull()?.takeIf { it >= 0 }

        private fun absent(e: SMBApiException): Boolean {
            val code = e.statusCode and 0xFFFFFFFFL
            return SmbErrors.kindForStatus(code) == SmbErrors.Kind.NOT_FOUND || code == STATUS_DELETE_PENDING
        }
    }

    companion object {
        const val RECEIPTS_DIRECTORY = "smb-operation-artifacts"
        const val IDLE_MS = 60_000L
        private const val SWEEP_MS = 20_000L
        const val MAX_CONCURRENT = 4
        private const val ATTR_DIRECTORY = 0x10L
        private const val ATTR_REPARSE = 0x400L
        private const val STATUS_DELETE_PENDING = 0xC0000056L

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
