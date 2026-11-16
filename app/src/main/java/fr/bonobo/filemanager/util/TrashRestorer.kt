package fr.bonobo.filemanager.util

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Restores verified copies, retaining trash data. Receipts prevent duplicate copies across restarts. */
class TrashRestorer(private val saf: SafFiles, private val trash: File,
    journal: File = saf.restoreJournalDirectory,
    private val alias: (String) -> File? = saf::localAlias,
    private val originAllowed: (File) -> Boolean = saf::localAliasAllowed,
    private val volumeOf: (String) -> Pair<String?, File?>? = saf::volumeOf,
    /** SPACE_CHECK on the destination before anything is written (source, target). */
    private val requireSpace: suspend (File, String) -> Unit = { source, target -> saf.requireSpace(source.path, target, restore = true) }) {
    companion object {
        /**
         * Compare the identity stored in a receipt with the current entry ([FileIdentity] rules).
         * Receipts written by older versions (« (dev=…,ino=…):<FileTime> ») are still understood: the
         * device/inode part is ignored (FUSE/vfat renumber it on remount), only the time is compared.
         */
        /**
         * Receipt key: the unique id of the trash entry (never its name or path: putting the same file
         * in the trash twice gives the same name, and the receipt of the first restoration would be
         * applied to the second entry). Entries of older versions have no id: their path is used.
         */
        fun receiptKey(entry: File): String = TrashPaths.entryId(entry.name)?.let { "entry:$it" } ?: entry.canonicalPath

        fun compare(stored: String, current: FileIdentity, legacyTime: () -> Long?): FileIdentity.Mismatch? {
            FileIdentity.decode(stored)?.let { return FileIdentity.compare(it, current) }
            if (stored.startsWith("(") && stored.contains("):")) {
                val recorded = runCatching { java.time.Instant.parse(stored.substringAfter("):")).toEpochMilli() }.getOrNull()
                    ?: return FileIdentity.Mismatch(FileIdentity.Field.FORMAT)
                val now = legacyTime() ?: return FileIdentity.Mismatch(FileIdentity.Field.FORMAT)
                return FileIdentity.compare(FileIdentity(modified = recorded), FileIdentity(modified = now))
            }
            return FileIdentity.Mismatch(FileIdentity.Field.FORMAT)
        }
    }
    private val receipts = RestoreReceipts(journal)
    private fun source(path: String): File = File(path).also {
        require(it.canonicalFile.parentFile == trash.canonicalFile && it.exists()) { "Entrée de corbeille inaccessible" }
        require(!Files.isSymbolicLink(it.toPath())) { "Lien de corbeille invalide" }
    }

    /**
     * @param force « Restaurer quand même » after a [RestoreMismatchException]. An existing destination
     *   is still never overwritten.
     */
    private suspend fun restorePrepared(path: String, force: Boolean, prepare: () -> String) {
        val source = source(path)
        val identity = FileIdentity.local(source, volumeOf)
        receipts.restore(receiptKey(source), identity.encode(),
            fingerprint = { IntegrityVerifier.fingerprint(LocalIntegrityAccess, source.path) },
            prepare = { prepare().also { target -> requireSpace(source, target) } }, exists = saf::restoreDestinationExists,
            verify = { target, digest ->
                check(IntegrityVerifier.fingerprint(saf.integrityAccess, target) == digest) { "Destination modifiée ou incomplète : original conservé, aucune nouvelle copie" }
            }, copy = { target -> saf.copy(source.path, target, SafFiles.CopyPolicy(spaceChecked = true)) },
            compare = { stored ->
                compare(stored, identity) {
                    runCatching { Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        .creationTime().toMillis() }.getOrNull()
                }
            }, force = force)
    }
    suspend fun restore(path: String, force: Boolean = false) = restorePrepared(path, force) {
        val target = TrashPaths.destination(TrashPaths.origin(File(path).name), trash)
        // Do not recreate missing volume paths, or trust a shared trash filename inside private app data.
        require(originAllowed(target)) { "Origine hors stockage autorisé : choisissez un dossier" }
        check(target.parentFile!!.isDirectory && target.parentFile!!.canRead()) { "Dossier d'origine disparu ou inaccessible : choisissez un dossier" }
        target.path
    }
    suspend fun restoreTo(path: String, parent: String, force: Boolean = false) = restorePrepared(path, force) {
        val localParent = if (SafFiles.isSaf(parent)) alias(parent)
            ?: error("Équivalence SAF indéterminée : choisissez un dossier du stockage Android") else File(parent)
        StorageEquivalence.requireOutsideTrash(localParent, trash)
        check(localParent.isDirectory && localParent.canRead()) { "Dossier choisi disparu ou inaccessible" }
        SafFiles.destination(parent, TrashPaths.displayName(File(path).name))
    }
}
