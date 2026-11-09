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
            return TransferInventory.inspect(saf.integrityAccess, source.ref.path) { path ->
                if (SafFiles.isSaf(path)) context.contentResolver.query(android.net.Uri.parse(path),
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n >= 0 } else null
                } else File(path).length()
            }
        }
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
            if (operation == TransferOperation.MOVE) saf.move(source.ref.path, target) else saf.copy(source.ref.path, target)
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
                is StorageRef.Local -> {
                    require(operation != TransferOperation.DUPLICATE) { "Duplication disponible uniquement vers SMB ; choisissez Copier pour le stockage local" }
                    remote.copyOrMoveToLocal(connection, item, destination.path, operation == TransferOperation.MOVE).getOrThrow()
                }
                is StorageRef.Remote -> {
                    require(destination.connectionId == connection.id && destination.protocol == connection.type.name) { "Transfert entre connexions non pris en charge" }
                    remote.copyOrMoveRemote(connection, item.path, destination.path, operation == TransferOperation.MOVE,
                        operation == TransferOperation.DUPLICATE).getOrThrow()
                }
                else -> error("Transfert réseau vers SAF non pris en charge par cette opération")
            }
        }
    }
    companion object {
        fun storage(path: String): StorageRef = if (SafFiles.isSaf(path)) StorageRef.SafDocument(path) else StorageRef.Local(path)
        fun source(item: FileItem) = TransferSource(storage(item.path), item.name, item.isDirectory)
        fun remoteSource(connection: RemoteConnection, item: FileItem) = TransferSource(
            StorageRef.Remote(connection.id, connection.type.name, item.path), item.name, item.isDirectory)
    }
}
