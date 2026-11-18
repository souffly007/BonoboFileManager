package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.domain.transfer.ConflictAction
import fr.bonobo.filemanager.domain.transfer.TransferSkipped
import fr.bonobo.filemanager.util.IntegrityVerifier
import fr.bonobo.filemanager.util.LocalIntegrityAccess
import fr.bonobo.filemanager.util.SafFiles
import fr.bonobo.filemanager.util.SafeFiles
import fr.bonobo.filemanager.util.SpaceCheck
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
import java.security.DigestOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFileRepository @Inject constructor(
    private val ftpRepository: FtpClientRepository,
    private val smbRepository: SmbClientRepository,
    private val sftpRepository: SftpClientRepository,
    private val webDavRepository: WebDavClientRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> {
        return when (connection.type) {
            ConnectionType.FTPS -> ftpRepository.listRemoteFiles(connection, path)
            ConnectionType.FTP -> Result.failure(Exception("FTP classique est désactivé. Utilisez FTPS sécurisé."))
            ConnectionType.SMB -> smbRepository.listRemoteFiles(connection, path)
            ConnectionType.SFTP -> sftpRepository.listRemoteFiles(connection, path)
            ConnectionType.WEBDAV -> webDavRepository.listRemoteFiles(connection, path)
            else -> Result.failure(Exception("Type de connexion non supporté pour le moment"))
        }
    }

    suspend fun deleteRemoteFile(connection: RemoteConnection, path: String): Result<Unit> {
        return when (connection.type) {
            ConnectionType.FTPS -> ftpRepository.deleteFile(connection, path)
            ConnectionType.FTP -> Result.failure(Exception("FTP classique est désactivé. Utilisez FTPS sécurisé."))
            ConnectionType.SMB -> smbRepository.deleteFile(connection, path)
            ConnectionType.SFTP -> sftpRepository.deleteFile(connection, path)
            ConnectionType.WEBDAV -> webDavRepository.deleteFile(connection, path)
            else -> Result.failure(Exception("Suppression non supportée pour ce type de connexion"))
        }
    }

    suspend fun copyOrMoveRemote(
        connection: RemoteConnection,
        sourcePath: String,
        destinationDirectory: String,
        move: Boolean,
        duplicate: Boolean = false
    ): Result<Unit> = when (connection.type) {
        ConnectionType.SMB -> smbRepository.copyOrMove(connection, sourcePath, destinationDirectory, move, duplicate)
        else -> Result.failure(Exception("Copier et déplacer sont disponibles pour SMB dans cette version"))
    }
    suspend fun inspectSmbTransfer(connection: RemoteConnection, path: String) = smbRepository.inspectTransfer(connection, path)

    /**
     * Server → phone (internal storage or SAF / USB key), file or SMB folder:
     *  1. metadata of the source (SMB: whole tree) read once;
     *  2. SPACE_CHECK of the private cache before the first byte;
     *  3. one download into the private cache, every file hashed DURING the stream and re-read locally;
     *  4. verified copy to the destination by [SafFiles] — « .bonobo-part », SHA-256, rename, cleanup,
     *     SPACE_CHECK of the destination and the conflict choice (Remplacer / Renommer / Ignorer / Fusionner);
     *  5. source unchanged (same metadata as in step 1, no second download);
     *  6. move: the source is deleted only if it is still unchanged at that moment.
     */
    suspend fun copyOrMoveToLocal(connection: RemoteConnection, item: FileItem, destinationDirectory: String, move: Boolean,
        conflict: ConflictAction = ConflictAction.ASK, compareHash: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val smb = connection.type == ConnectionType.SMB
            require(!item.isDirectory || smb) { "Transfert de dossier non pris en charge pour ce protocole" }
            if (!SafFiles.isSaf(destinationDirectory)) {
                val dir = java.io.File(destinationDirectory)
                require(dir.isDirectory && dir.canRead()) { "Dossier de destination inaccessible" }
            }
            SafFiles.validateName(item.name)
            val storage = if (smb) TechnicalJournal.Storage.SMB else TechnicalJournal.Storage.OTHER
            val mode = if (move) TechnicalJournal.Mode.MOVE else TechnicalJournal.Mode.COPY
            val operation = Diagnostic.operation()
            val workspace = java.io.File(context.cacheDir, "verified-network-" + java.util.UUID.randomUUID())
            check(workspace.mkdirs()) { "Cache privé inaccessible" }
            try {
                val local = SafeFiles.child(workspace, item.name)
                var unchanged: suspend () -> Unit
                var deleteSource: suspend () -> Unit
                if (smb) {
                    // 1. Tree metadata, 2. cache space, 3. download hashed during the stream.
                    val snapshot = smbRepository.snapshot(connection, item.path).getOrThrow()
                    val files = snapshot.values.filter { !it.directory }
                    SpaceCheck.require(context, context.cacheDir.path, files.sumOf { it.size }, files.size.toLong(), operation, mode, storage = storage)
                    smbRepository.downloadTree(connection, item.path, local, snapshot).getOrThrow()
                    unchanged = {
                        check(smbRepository.snapshot(connection, item.path).getOrThrow() == snapshot) {
                            "Source distante modifiée pendant la copie : source conservée"
                        }
                    }
                    deleteSource = { smbRepository.deleteTreeIfUnchanged(connection, item.path, snapshot).getOrThrow() }
                } else {
                    val parent = item.path.replace('\\', '/').substringBeforeLast('/', "")
                    suspend fun metadata(): FileItem =
                        listRemoteFiles(connection, parent).getOrThrow().singleOrNull { it.name == item.name }
                            ?: error("Source distante disparue ou ambiguë")
                    val before = metadata()
                    require(!before.isDirectory && before.size >= 0) { "Type ou taille distante indéterminés" }
                    SpaceCheck.require(context, context.cacheDir.path, before.size, 1, operation, mode, storage = storage)
                    val observer = TransferEvents.observer()
                    val operationContext = currentCoroutineContext()
                    var previous = 0L
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, item.path, item.name, fileBytes = 0, fileTotal = before.size, fileStart = true))
                    val digest = SmbTransfers.sha256()
                    DigestOutputStream(local.outputStream(), digest).use { output ->
                        download(connection, item.path, output, before.size, { operationContext.ensureActive() }, { bytes ->
                            observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = bytes - previous, fileBytes = bytes))
                            previous = bytes
                        })
                    }
                    check(local.length() == before.size) { "Lecture réseau incomplète : source conservée" }
                    val streamed = SmbTransfers.hex(digest.digest())
                    check(IntegrityVerifier.fingerprint(LocalIntegrityAccess, local.path) == streamed) { "Copie temporaire différente des octets reçus : source conservée" }
                    unchanged = {
                        val after = metadata()
                        check(!after.isDirectory && after.size == before.size && after.lastModified.time == before.lastModified.time) {
                            "Source distante modifiée pendant la copie : source conservée"
                        }
                    }
                    deleteSource = {
                        when (connection.type) {
                            ConnectionType.WEBDAV -> webDavRepository.deleteVerifiedFile(connection, item.path, streamed)
                            ConnectionType.SFTP -> sftpRepository.deleteVerifiedFile(connection, item.path, streamed).getOrThrow()
                            else -> deleteRemoteFile(connection, item.path).getOrThrow()
                        }
                    }
                }

                // 4. Verified copy to the destination; network bytes were counted once.
                TransferEvents.emit(TransferEvents.Event(TransferPhase.PREPARING))
                val saf = SafFiles(context)
                withContext(TransferEvents {}) {
                    saf.copy(local.path, SafFiles.destination(destinationDirectory, item.name),
                        SafFiles.CopyPolicy(conflict, compareHash || move))
                }
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))

                // 5. Source unchanged during the download. 6. Move.
                unchanged()
                currentCoroutineContext().ensureActive()
                if (move) {
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                    currentCoroutineContext().ensureActive()
                    deleteSource()
                }
            } finally { workspace.deleteRecursively() }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /**
     * Phone (internal storage or SAF / USB key) → SMB share: verified on the server (« .bonobo-part »,
     * SHA-256, rename, cleanup) with the conflict choice. Move: the source is deleted only after the
     * upload is verified AND the source re-read unchanged.
     */
    suspend fun uploadToSmb(connection: RemoteConnection, sourcePath: String, name: String, destinationDirectory: String,
        move: Boolean, conflict: ConflictAction, compareHash: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(connection.type == ConnectionType.SMB) { "Envoi disponible vers SMB uniquement" }
            val saf = SafFiles(context)
            val source = uploadSource(saf)
            val result = smbRepository.upload(connection, source, sourcePath, name, destinationDirectory, conflict, compareHash, move).getOrThrow()
            if (move) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                smbTransfersVerify(source, sourcePath, result.hashes)
                currentCoroutineContext().ensureActive()
                TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                deleteLocalSource(saf, sourcePath)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException || it is TransferSkipped) throw it }
    }

    private suspend fun smbTransfersVerify(source: SmbTransfers.UploadSource, path: String, hashes: Map<String, String>) =
        SmbTransfers(java.io.File(context.filesDir, SmbClientRepository.RECEIPTS_DIRECTORY)).verifyUnchanged(source, path, hashes)

    /** Internal storage or SAF document read through the same access as the USB copies. */
    private fun uploadSource(saf: SafFiles): SmbTransfers.UploadSource = object : SmbTransfers.UploadSource {
        override fun children(path: String) = saf.integrityAccess.children(path)
        override fun open(path: String) = saf.integrityAccess.open(path)
        override fun identity(path: String) = saf.integrityAccess.identity(path)
        override fun size(path: String): Long = if (SafFiles.isSaf(path)) {
            context.contentResolver.query(android.net.Uri.parse(path),
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            } ?: error("Taille du fichier source inconnue")
        } else java.io.File(path).length()
    }

    /** Deletes the moved source (verified absent), local file / folder or SAF document. */
    private fun deleteLocalSource(saf: SafFiles, path: String) {
        if (SafFiles.isSaf(path)) { saf.delete(path); return }
        val file = java.io.File(path)
        val accepted = fr.bonobo.filemanager.util.FileUtils.deleteRecursively(file)
        check(accepted && !SafeFiles.exists(file)) { "Suppression de la source non confirmée : elle est toujours sur le téléphone" }
    }

    /** Free bytes on the SMB share of [path], null when unknown. */
    suspend fun smbFreeSpace(connection: RemoteConnection, path: String): Long? =
        if (connection.type == ConnectionType.SMB) smbRepository.freeSpace(connection, path) else null

    suspend fun createFolder(connection: RemoteConnection, parentPath: String, name: String): Result<String> = when (connection.type) {
        ConnectionType.SMB -> smbRepository.createFolder(connection, parentPath, name)
        else -> Result.failure(Exception("Création de dossier disponible pour SMB dans cette version"))
    }

    suspend fun rename(connection: RemoteConnection, path: String, newName: String): Result<String> = when (connection.type) {
        ConnectionType.SMB -> smbRepository.rename(connection, path, newName)
        else -> Result.failure(Exception("Renommage disponible pour SMB dans cette version"))
    }

    /**
     * [thumbnail] only changes the SMB journal operation (THUMBNAIL instead of OPEN).
     * [maxBytes] (SMB): refuse a larger file and never read more than that.
     */
    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File,
        thumbnail: Boolean = false, maxBytes: Long? = null): Result<java.io.File> {
        return when (connection.type) {
            ConnectionType.SMB -> smbRepository.downloadToCache(connection, path, destination, thumbnail, maxBytes)
            ConnectionType.SFTP -> sftpRepository.downloadToFile(connection, path, destination)
            ConnectionType.WEBDAV -> webDavRepository.downloadToFile(connection, path, destination)
            ConnectionType.FTPS -> runCatching {
                destination.outputStream().use { output ->
                    ftpRepository.download(connection, path, output, 0L, {}, {})
                }
                destination
            }
            else -> Result.failure(Exception("Ouverture directe non disponible pour ce type de connexion"))
        }
    }
    suspend fun uploadFromCache(connection: RemoteConnection, path: String, source: java.io.File): Result<Unit> = when (connection.type) {
        ConnectionType.SMB -> smbRepository.uploadFromCache(connection, path, source)
        ConnectionType.SFTP -> sftpRepository.uploadFromFile(connection, path, source)
        ConnectionType.WEBDAV -> webDavRepository.uploadFromFile(connection, path, source)
        else -> Result.failure(Exception("Enregistrement distant disponible uniquement pour SMB"))
    }

    suspend fun download(connection: RemoteConnection, path: String, output: java.io.OutputStream,
        expectedSize: Long, checkCancelled: () -> Unit, progress: (Long) -> Unit) {
        when (connection.type) {
            ConnectionType.FTPS -> ftpRepository.download(connection, path, output, expectedSize, checkCancelled, progress)
            ConnectionType.FTP -> error("FTP classique est désactivé. Utilisez FTPS sécurisé.")
            ConnectionType.SMB -> smbRepository.download(connection, path, output, expectedSize, checkCancelled, progress).getOrThrow()
            ConnectionType.SFTP -> sftpRepository.download(connection, path, output, expectedSize, checkCancelled, progress)
            ConnectionType.WEBDAV -> webDavRepository.download(connection, path, output, checkCancelled, progress)
            else -> error("Téléchargement non disponible pour ce protocole")
        }
    }

    /** SMB: partial files left on this server by an earlier failure or a killed app. */
    suspend fun cleanPending(connection: RemoteConnection) {
        if (connection.type == ConnectionType.SMB) smbRepository.cleanPending(connection)
    }

    /** Leaving the explorer: SMB closes its session (now, or when the running transfer ends). */
    fun release(connection: RemoteConnection) {
        if (connection.type == ConnectionType.SMB) smbRepository.release(connection)
    }
}
