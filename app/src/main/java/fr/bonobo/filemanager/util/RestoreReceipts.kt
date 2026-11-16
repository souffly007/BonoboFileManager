package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class AlreadyRestoredException : IllegalStateException("Déjà restauré et vérifié. Aucune nouvelle copie créée ; original conservé dans la corbeille.")

/**
 * The trash entry no longer matches the receipt of a previous restoration. Nothing was copied;
 * the user may choose « Restaurer quand même » (restore again with force = true).
 */
class RestoreMismatchException(val mismatch: FileIdentity.Mismatch) :
    IllegalStateException("Ne correspond plus à la restauration précédente : ${mismatch.describe()}.")

/** Private durable intent before copy, durable receipt only after verification. No automatic deletion. */
class RestoreReceipts(private val directory: File) {
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
    private data class Receipt(val identity: String, val digest: String, val target: String, val verified: Boolean)
    private fun key(path: String) = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun read(file: File): Receipt? {
        if (!file.exists()) return null
        val p = Properties().apply { file.inputStream().use { load(it) } }
        require(p.getProperty("version") == "1" && p.getProperty("state") in listOf("PENDING", "VERIFIED")) { "Reçu de restauration illisible : aucune nouvelle copie" }
        fun required(k: String) = p.getProperty(k)?.takeIf { it.isNotBlank() } ?: error("Reçu de restauration incomplet")
        return Receipt(required("identity"), required("digest"), required("target"), p.getProperty("state") == "VERIFIED")
    }
    private fun write(file: File, receipt: Receipt) {
        check(directory.isDirectory || directory.mkdirs()) { "Journal de restauration inaccessible" }
        val temp = File.createTempFile("receipt-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                Properties().apply {
                    setProperty("version", "1"); setProperty("identity", receipt.identity)
                    setProperty("digest", receipt.digest); setProperty("target", receipt.target)
                    setProperty("state", if (receipt.verified) "VERIFIED" else "PENDING")
                }.store(output, null)
                output.fd.sync()
            }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }

    /**
     * @param compare recorded identity → why it differs from [identity] (null = same entry).
     *   Default: identical strings only.
     * @param force « Restaurer quand même »: a mismatching receipt is ignored (and replaced).
     *   Existing files are still never overwritten (prepare / copy refuse an occupied destination).
     */
    /** @param source receipt key: the trash entry's unique id ([TrashRestorer.receiptKey]). */
    suspend fun restore(source: String, identity: String, fingerprint: suspend () -> String,
        prepare: suspend () -> String, exists: (String) -> Boolean,
        verify: suspend (String, String) -> Unit, copy: suspend (String) -> String,
        compare: (String) -> FileIdentity.Mismatch? = { stored -> if (stored == identity) null else FileIdentity.Mismatch(FileIdentity.Field.FORMAT) },
        force: Boolean = false) {
        locks.getOrPut(directory.canonicalPath) { Mutex() }.withLock {
            val file = File(directory, key(source) + ".properties")
            val digest = fingerprint()
            val stored = read(file)
            var previous = stored
            if (stored != null) {
                val mismatch = compare(stored.identity)
                    ?: if (stored.digest != digest) FileIdentity.Mismatch(FileIdentity.Field.CONTENT) else null
                // The copy restored under that receipt no longer exists (and its place is observable):
                // nothing could be duplicated, the receipt belongs to an earlier entry. A destination
                // that cannot be observed (key removed) is NOT absence: the user is asked.
                val stale = mismatch != null && !force && runCatching { !exists(stored.target) }.getOrDefault(false)
                if (stale) {
                    Diagnostic.event(Event.RESTORE_IDENTITY, Result.STALE_RECEIPT,
                        expected = mismatch!!.expected, transferred = mismatch.found)
                    previous = null
                } else if (mismatch != null) {
                    // Expected vs found, in the closed vocabulary of the journal.
                    Diagnostic.event(Event.RESTORE_IDENTITY, if (force) Result.FORCED else mismatch.result(),
                        expected = mismatch.expected, transferred = mismatch.found)
                    if (!force) throw RestoreMismatchException(mismatch)
                    previous = null // explicit user decision: treat as a new entry
                } else if (stored.identity != identity) {
                    write(file, stored.copy(identity = identity)) // equivalent older format: upgrade once
                }
            }
            if (previous != null && exists(previous.target)) {
                check(previous.verified) { "Restauration interrompue ou incertaine : contrôlez la destination précédente avant de recommencer" }
                verify(previous.target, digest)
                throw AlreadyRestoredException()
            }
            val target = prepare()
            write(file, Receipt(identity, digest, target, false))
            val actual = copy(target)
            verify(actual, digest)
            check(fingerprint() == digest) { "Source modifiée pendant la restauration : original conservé" }
            write(file, Receipt(identity, digest, actual, true))
        }
    }
}
