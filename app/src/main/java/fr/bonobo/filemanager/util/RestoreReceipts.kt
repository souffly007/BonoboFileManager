package fr.bonobo.filemanager.util

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
    suspend fun restore(source: String, identity: String, fingerprint: suspend () -> String,
        prepare: () -> String, exists: (String) -> Boolean,
        verify: suspend (String, String) -> Unit, copy: suspend (String) -> String) {
        locks.getOrPut(directory.canonicalPath) { Mutex() }.withLock {
            val file = File(directory, key(source) + ".properties")
            val digest = fingerprint()
            val previous = read(file)?.takeIf { it.identity == identity }
            if (previous != null) {
                check(previous.digest == digest) { "Entrée de corbeille modifiée : restauration manuelle nécessaire" }
                if (exists(previous.target)) {
                    check(previous.verified) { "Restauration interrompue ou incertaine : contrôlez la destination précédente avant de recommencer" }
                    verify(previous.target, digest)
                    throw AlreadyRestoredException()
                }
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
