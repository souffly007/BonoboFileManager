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
import fr.bonobo.filemanager.util.IntegrityVerifier
import fr.bonobo.filemanager.util.LocalIntegrityAccess
import fr.bonobo.filemanager.util.SafeFiles
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
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

    /** Fresh remote reads and destination re-read precede every requested source deletion. */
    suspend fun copyOrMoveToLocal(connection: RemoteConnection, item: FileItem,
        destinationDirectory: String, move: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!item.isDirectory) { "Transfert de dossier réseau vers local non pris en charge" }
            val dir = java.io.File(destinationDirectory)
            require(dir.isDirectory && dir.canRead()) { "Dossier de destination inaccessible" }
            val target = SafeFiles.child(dir, item.name)
            require(!SafeFiles.exists(target)) { "La destination existe déjà : aucun remplacement" }
            val temporary = java.io.File.createTempFile("verified-network-", ".tmp", context.cacheDir)
            val observer = TransferEvents.observer()
            suspend fun snapshot(measure: Boolean = false): String {
                val parent = item.path.replace('\\', '/').substringBeforeLast('/', "")
                val metadata = listRemoteFiles(connection, parent).getOrThrow().singleOrNull { it.name == item.name }
                    ?: error("Source distante disparue ou ambiguë")
                require(!metadata.isDirectory && metadata.size >= 0) { "Type ou taille distante indéterminés" }
                val operationContext = currentCoroutineContext()
                var previous = 0L
                if (measure) observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, item.path, item.name, fileBytes = 0, fileTotal = metadata.size, fileStart = true))
                temporary.outputStream().use { output ->
                    download(connection, item.path, output, metadata.size, { operationContext.ensureActive() }, { bytes ->
                        if (measure) observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = bytes - previous, fileBytes = bytes))
                        previous = bytes
                    })
                }
                check(temporary.length() == metadata.size) { "Lecture réseau incomplète : source conservée" }
                return IntegrityVerifier.fingerprint(LocalIntegrityAccess, temporary.path)
            }
            try {
                val before = snapshot(measure = true)
                val saf = fr.bonobo.filemanager.util.SafFiles(context)
                // Network bytes are counted once; private cache recopy is not another network transfer.
                TransferEvents.emit(TransferEvents.Event(TransferPhase.PREPARING))
                withContext(TransferEvents {}) { saf.copy(temporary.path, target.path) }
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                check(IntegrityVerifier.fingerprint(LocalIntegrityAccess, target.path) == before) { "Destination incomplète : source conservée" }
                check(snapshot() == before) { "Source distante modifiée : suppression refusée" }
                currentCoroutineContext().ensureActive()
                if (move) {
                    // Recheck the destination immediately before entering the destructive phase.
                    check(IntegrityVerifier.fingerprint(LocalIntegrityAccess, target.path) == before) { "Destination modifiée : source conservée" }
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                    currentCoroutineContext().ensureActive()
                    when (connection.type) {
                        ConnectionType.WEBDAV -> webDavRepository.deleteVerifiedFile(connection, item.path, before)
                        ConnectionType.SFTP -> sftpRepository.deleteVerifiedFile(connection, item.path, before).getOrThrow()
                        ConnectionType.SMB -> smbRepository.deleteVerifiedFile(connection, item.path, before).getOrThrow()
                        else -> deleteRemoteFile(connection, item.path).getOrThrow()
                    }
                }
            } finally { temporary.delete() }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    suspend fun downloadToCache(connection: RemoteConnection, path: String, destination: java.io.File): Result<java.io.File> {
        return when (connection.type) {
            ConnectionType.SMB -> smbRepository.downloadToCache(connection, path, destination)
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

}
