package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.DeletionTrace
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import java.io.File

/**
 * Put a local element in the trash (internal storage, « .bonobo_trash »).
 *
 *  - Same volume as the trash: a plain rename (nothing is copied, nothing can be lost).
 *  - Other volume (USB key…): the element goes through the verified copy circuit — SPACE_CHECK on
 *    the internal storage, « .bonobo-part », SHA-256, rename — and the source is deleted only after
 *    that verification ([verifiedMove], i.e. [SafFiles.move]). Never a raw cross-device move.
 *
 * The trace carries the storage of the SOURCE (storage=USB for a key), since that is what is deleted.
 */
class TrashMover(
    private val trash: File,
    /** Root of the volume holding a path (null if unknown: then treated as another volume). */
    private val volumeRoot: (String) -> File?,
    private val storageOf: (String) -> Storage,
    /** Throws when the trash volume cannot hold the element (logs SPACE_CHECK). */
    private val requireSpace: (File) -> Unit,
    /** Verified copy + source deletion after verification. */
    private val verifiedMove: suspend (source: String, target: String) -> Unit,
    private val newId: () -> String = TrashPaths::newId
) {
    /** @return the trash entry created. */
    suspend fun move(path: String): File {
        val source = File(path)
        val trace = DeletionTrace(storageOf(path),
            if (source.isDirectory) Kind.DIRECTORY else if (source.exists()) Kind.FILE else Kind.UNKNOWN, Mode.TRASH)
        trace.start()
        try {
            require(source.exists()) { "Élément introuvable : actualisez." }
            require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Lien symbolique : utilisez Supprimer." }
            check(trash.isDirectory || trash.mkdirs()) { "Corbeille locale inaccessible : création refusée" }
            require(!source.canonicalFile.toPath().startsWith(trash.canonicalFile.toPath())) { "Déjà dans la corbeille" }
            // Unique id per entry: restore receipts are attached to it, never to the name.
            var destination = File(trash, TrashPaths.entryName(source.absolutePath, source.name, newId()))
            while (SafeFiles.exists(destination)) destination = File(trash, TrashPaths.entryName(source.absolutePath, source.name, newId()))
            if (sameVolume(source)) {
                java.nio.file.Files.move(source.toPath(), destination.toPath()) // rename, refuses an existing target
            } else {
                requireSpace(source)
                verifiedMove(source.path, destination.path)
            }
            trace.provider(Result.OK)
            val verified = !SafeFiles.exists(source) && destination.exists()
            trace.verify(if (verified) Result.OK else Result.INDETERMINATE)
            trace.end(if (verified) Result.OK else Result.INDETERMINATE)
            check(verified) { "Mise à la corbeille non confirmée ; actualisez." }
            return destination
        } catch (e: Exception) {
            trace.end(if (e is kotlinx.coroutines.CancellationException) Result.CANCELLED else Result.INDETERMINATE)
            throw e
        }
    }

    private fun sameVolume(source: File): Boolean {
        val from = volumeRoot(source.canonicalPath)?.canonicalFile ?: return false
        val to = volumeRoot(trash.canonicalPath)?.canonicalFile ?: return false
        return from == to
    }
}
