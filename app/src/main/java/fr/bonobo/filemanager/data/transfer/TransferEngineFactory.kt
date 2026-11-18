package fr.bonobo.filemanager.data.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.data.repository.RemoteFileRepository
import fr.bonobo.filemanager.domain.model.*
import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.util.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferEngineFactory @Inject constructor(@ApplicationContext private val context: Context,
    private val remote: RemoteFileRepository) {
    fun local() = object : TransferEngine {
        private val saf = SafFiles(context)
        private fun alias(ref: StorageRef): File? = when (ref) {
            is StorageRef.Local -> File(ref.path).canonicalFile
            is StorageRef.SafDocument -> runCatching { saf.localAlias(ref.path)?.canonicalFile }.getOrNull()
            else -> null
        }
        override suspend fun normalize(sources: List<TransferSource>): List<TransferSource> {
            val unique = sources.distinctBy { alias(it.ref)?.path ?: saf.integrityAccess.identity(it.ref.path) }
            val paths = unique.associateWith { alias(it.ref)?.toPath() }
            val selectedIds = unique.associateBy { saf.integrityAccess.identity(it.ref.path) }
            val covered = mutableSetOf<TransferSource>()
            // Document IDs are opaque. Walk actual children rather than guessing an ID prefix.
            for (root in unique.filter { it.directory && it.ref is StorageRef.SafDocument }) {
                val visited = mutableSetOf<String>()
                suspend fun visit(path: String, depth: Int = 0) {
                    require(depth <= TransferInventory.MAX_DEPTH) { "Arborescence trop profonde : transfert refusé" }
                    currentCoroutineContext().ensureActive()
                    require(visited.add(saf.integrityAccess.identity(path))) { "Cycle SAF : sélection refusée" }
                    for (child in saf.integrityAccess.children(path).orEmpty()) {
                        selectedIds[saf.integrityAccess.identity(child.path)]?.let { if (it != root) covered += it }
                        if (saf.integrityAccess.children(child.path) != null) visit(child.path, depth + 1)
                    }
                }
                visit(root.ref.path)
            }
            return unique.filter { source ->
                val path = paths[source]
                source !in covered && unique.none { other -> other != source && other.directory && path != null && paths[other] != null && path.startsWith(paths[other]!!) }
            }
        }
        override suspend fun inspect(source: TransferSource): Inventory {
            return TransferInventory.inspect(saf.integrityAccess, source.ref.path, source.name) { path ->
                if (SafFiles.isSaf(path)) context.contentResolver.query(android.net.Uri.parse(path),
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n >= 0 } else null
                } else File(path).length()
            }
        }
        /**
         * Refuse before writing: total size vs. free space of the destination volume (with a margin),
         * and the FAT32 4 GiB-per-file limit when the file system can be identified.
         */
        override suspend fun preflight(request: TransferRequest, inventories: Map<StorageRef, Inventory>) {
            spaceChecked = false
            if (request.destination is StorageRef.Remote) return
            val destination = request.destination.path
            val volume = runCatching { UsbVolumes.forPath(context, destination) }.getOrNull()
            val usb = UsbVolumes.isUsb(volume)
            val storage = UsbVolumes.storage(context, destination)
            val known = request.sources.mapNotNull { inventories[it.ref] }
            // FAT32: one file of 4 GiB or more can never be written.
            val fileSystem = UsbVolumes.fileSystem(volume)
            val largest = known.filter { it.largest != null }.maxByOrNull { it.largest!! }
            if (largest?.largest != null && largest.largest > UsbVolumes.FAT32_MAX_FILE && UsbVolumes.isFat32(fileSystem)) {
                fr.bonobo.filemanager.diagnostic.Diagnostic.detail(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.FILE_TOO_LARGE,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED, storage = storage,
                    expected = largest.largest, transferred = UsbVolumes.FAT32_MAX_FILE)
                throw TransferFailure(FailureKind.FILE_TOO_LARGE,
                    "« ${largest.largestName ?: "Un fichier"} » fait ${TransferText.size(largest.largest)} : " +
                        (if (usb) "la clé USB est formatée" else "le support est formaté") + " en FAT32, " +
                        "qui n’accepte pas les fichiers de 4 Go ou plus. Reformatez-la en exFAT ou choisissez un autre support.", usb = usb)
            }
            // A merge may skip most files: the real need is unknown, rely on DISK_FULL detection.
            if (request.sources.any { it.conflict == ConflictAction.MERGE }) return
            if (known.size != request.sources.size || known.any { it.bytes == null }) return
            SpaceCheck.require(context, destination, known.sumOf { it.bytes!! }, known.sumOf { it.files },
                mode = if (request.operation == TransferOperation.MOVE) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.MOVE
                    else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.COPY,
                replacing = request.sources.any { it.conflict == ConflictAction.REPLACE }, storage = storage)
            spaceChecked = true
        }
        /** The whole batch passed SPACE_CHECK: each root does not repeat it. */
        @Volatile private var spaceChecked = false
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            require(destination !is StorageRef.Remote) { "Transfert vers réseau non pris en charge" }
            // Never permit local/SAF aliases to place a folder inside its own tree.
            val original = alias(source.ref)
            val parent = alias(destination)
            if (source.directory && original != null && parent != null) {
                require(!parent.toPath().startsWith(original.toPath())) { "Destination dans la source : transfert refusé" }
            }
            val target = SafFiles.destination(destination.path, source.name)
            require(operation != TransferOperation.DUPLICATE) { "Choisissez un autre dossier : aucun remplacement" }
            val policy = SafFiles.CopyPolicy(source.conflict, source.compareHash, spaceChecked)
            if (operation == TransferOperation.MOVE) saf.move(source.ref.path, target, policy) else saf.copy(source.ref.path, target, policy)
        }
    }
    /**
     * USB (or other volume) → internal trash, shown with the same progress card as a copy: inventory,
     * copy %, « Vérification : N % », deletion of the source. [move] is the repository's verified trash
     * move (TrashMover: SPACE_CHECK, .bonobo-part, SHA-256, rename, then source deletion); the progress
     * events reach the card through the coroutine context.
     */
    fun trash(move: suspend (String) -> Unit) = object : TransferEngine {
        private val base = local()
        override suspend fun normalize(sources: List<TransferSource>) = base.normalize(sources)
        override suspend fun inspect(source: TransferSource) = base.inspect(source)
        // SPACE_CHECK is done per element by TrashMover, on the internal storage.
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            require(operation == TransferOperation.TRASH && source.ref is StorageRef.Local) { "Mise à la corbeille : élément local uniquement" }
            move(source.ref.path)
        }
    }
    /**
     * Trash entry → its origin (or a chosen folder), with the same progress card as a copy. [restore] is
     * the repository's TrashRestorer (receipt, SPACE_CHECK on the destination, .bonobo-part, SHA-256,
     * rename); the trash entry is never deleted. Cancelling the card cancels the copy and its cleanup.
     */
    fun restore(restore: suspend (TransferSource) -> Unit) = object : TransferEngine {
        private val base = local()
        override suspend fun normalize(sources: List<TransferSource>) = sources.distinctBy { it.ref }
        override suspend fun inspect(source: TransferSource) = base.inspect(source)
        // SPACE_CHECK is done per element by TrashRestorer, once its destination is known.
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            require(operation == TransferOperation.RESTORE && source.ref is StorageRef.Local) { "Restauration : entrée de corbeille locale uniquement" }
            restore(source)
            TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING, note = "Original conservé dans la corbeille."))
        }
    }
    fun network(connection: RemoteConnection, items: List<FileItem>) = object : TransferEngine {
        override suspend fun normalize(sources: List<TransferSource>): List<TransferSource> {
            fun path(source: TransferSource) = source.ref.path.replace('\\', '/').trimEnd('/')
            val unique = if (connection.type == ConnectionType.SMB) sources.distinctBy(::path) else sources.distinctBy { it.ref }
            return if (connection.type == ConnectionType.SMB) unique.filter { source ->
                unique.none { it != source && it.directory && path(source).startsWith(path(it) + "/") }
            } else unique
        }
        override suspend fun inspect(source: TransferSource): Inventory {
            val item = items.single { it.path == source.ref.path }
            if (connection.type == ConnectionType.SMB) return remote.inspectSmbTransfer(connection, item.path).getOrThrow()
            require(!item.isDirectory) { "Transfert de dossier non pris en charge pour ce protocole" }
            return Inventory(1, item.size.takeIf { it >= 0 })
        }
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            val item = items.single { it.path == source.ref.path }
            when (destination) {
                // Internal storage or USB key (SAF): verified copy with the conflict choice.
                is StorageRef.Local, is StorageRef.SafDocument -> {
                    require(operation != TransferOperation.DUPLICATE) { "Duplication disponible uniquement vers SMB ; choisissez Copier pour le téléphone" }
                    remote.copyOrMoveToLocal(connection, item, destination.path, operation == TransferOperation.MOVE,
                        source.conflict, source.compareHash).getOrThrow()
                }
                is StorageRef.Remote -> {
                    require(destination.connectionId == connection.id && destination.protocol == connection.type.name) { "Transfert entre connexions non pris en charge" }
                    remote.copyOrMoveRemote(connection, item.path, destination.path, operation == TransferOperation.MOVE,
                        operation == TransferOperation.DUPLICATE).getOrThrow()
                }
            }
        }
    }

    /**
     * Phone (internal storage or USB key) → SMB share. Same card, cancel, retry and conflict choices as a
     * copy to the USB key; the server side is verified (« .bonobo-part », SHA-256, rename, cleanup).
     * Preflight: free space of the share when the server reports it (SPACE_CHECK storage=SMB).
     */
    fun upload(connection: RemoteConnection) = object : TransferEngine {
        private val base = local()
        override suspend fun normalize(sources: List<TransferSource>) = base.normalize(sources)
        override suspend fun inspect(source: TransferSource) = base.inspect(source)
        override suspend fun preflight(request: TransferRequest, inventories: Map<StorageRef, Inventory>) {
            val destination = request.destination as? StorageRef.Remote ?: return
            // A merge may skip most files: the real need is unknown.
            if (request.sources.any { it.conflict == ConflictAction.MERGE || it.conflict == ConflictAction.REPLACE && it.directory }) return
            val known = request.sources.mapNotNull { inventories[it.ref] }
            if (known.size != request.sources.size || known.any { it.bytes == null }) return
            val needed = known.sumOf { it.bytes!! }
            val files = known.sumOf { it.files }
            val mode = if (request.operation == TransferOperation.MOVE) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.MOVE
                else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.COPY
            val available = remote.smbFreeSpace(connection, destination.path)
            if (available == null) {
                fr.bonobo.filemanager.diagnostic.Diagnostic.detail(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SPACE_CHECK,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.UNKNOWN, storage = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SMB,
                    mode = mode, expected = needed + SpaceCheck.margin(needed, files))
                return
            }
            val ok = SpaceCheck.enough(needed, files, available)
            fr.bonobo.filemanager.diagnostic.Diagnostic.detail(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.SPACE_CHECK,
                if (ok) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.OK else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.INSUFFICIENT_SPACE,
                storage = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SMB, mode = mode,
                expected = needed + SpaceCheck.margin(needed, files), transferred = available)
            if (!ok) throw TransferFailure(FailureKind.INSUFFICIENT_SPACE,
                "${TransferText.size(needed)} à envoyer, ${TransferText.size(available)} libres sur le partage SMB. " +
                    "Libérez de l’espace sur le serveur ou envoyez moins d’éléments.")
        }
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            require(destination is StorageRef.Remote && destination.connectionId == connection.id &&
                connection.type == ConnectionType.SMB) { "Envoi disponible vers la connexion SMB ouverte uniquement" }
            require(source.ref !is StorageRef.Remote) { "Source réseau : utilisez Copier depuis l’explorateur réseau" }
            require(operation == TransferOperation.COPY || operation == TransferOperation.MOVE) { "Opération non prise en charge pour un envoi" }
            remote.uploadToSmb(connection, source.ref.path, source.name, destination.path, operation == TransferOperation.MOVE,
                source.conflict, source.compareHash).getOrThrow()
        }
    }
    companion object {
        fun storage(path: String): StorageRef = if (SafFiles.isSaf(path)) StorageRef.SafDocument(path) else StorageRef.Local(path)
        fun source(item: FileItem) = TransferSource(storage(item.path), item.name, item.isDirectory)
        fun remoteSource(connection: RemoteConnection, item: FileItem) = TransferSource(
            StorageRef.Remote(connection.id, connection.type.name, item.path), item.name, item.isDirectory)
    }
}
