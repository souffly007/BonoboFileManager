package fr.bonobo.filemanager.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Restores verified copies, retaining trash data. Receipts prevent duplicate copies across restarts. */
class TrashRestorer(private val saf: SafFiles, private val trash: File,
    journal: File = saf.restoreJournalDirectory,
    private val alias: (String) -> File? = saf::localAlias,
    private val originAllowed: (File) -> Boolean = saf::localAliasAllowed) {
    private val receipts = RestoreReceipts(journal)
    private fun source(path: String): File = File(path).also {
        require(it.canonicalFile.parentFile == trash.canonicalFile && it.exists()) { "Entrée de corbeille inaccessible" }
        require(!Files.isSymbolicLink(it.toPath())) { "Lien de corbeille invalide" }
    }
    private suspend fun restorePrepared(path: String, prepare: () -> String) {
        val source = source(path)
        val attributes = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)
        val identity = "${attributes.fileKey()}:${attributes.creationTime()}"
        receipts.restore(source.canonicalPath, identity,
            fingerprint = { IntegrityVerifier.fingerprint(LocalIntegrityAccess, source.path) },
            prepare = prepare, exists = saf::restoreDestinationExists,
            verify = { target, digest ->
                check(IntegrityVerifier.fingerprint(saf.integrityAccess, target) == digest) { "Destination modifiée ou incomplète : original conservé, aucune nouvelle copie" }
            }, copy = { target -> saf.copy(source.path, target) })
    }
    suspend fun restore(path: String) = restorePrepared(path) {
        val target = TrashPaths.destination(TrashPaths.origin(File(path).name), trash)
        // Do not recreate missing volume paths, or trust a shared trash filename inside private app data.
        require(originAllowed(target)) { "Origine hors stockage autorisé : choisissez un dossier" }
        check(target.parentFile!!.isDirectory && target.parentFile!!.canRead()) { "Dossier d'origine disparu ou inaccessible : choisissez un dossier" }
        target.path
    }
    suspend fun restoreTo(path: String, parent: String) = restorePrepared(path) {
        val localParent = if (SafFiles.isSaf(parent)) alias(parent)
            ?: error("Équivalence SAF indéterminée : choisissez un dossier du stockage Android") else File(parent)
        StorageEquivalence.requireOutsideTrash(localParent, trash)
        check(localParent.isDirectory && localParent.canRead()) { "Dossier choisi disparu ou inaccessible" }
        SafFiles.destination(parent, TrashPaths.displayName(File(path).name))
    }
}
