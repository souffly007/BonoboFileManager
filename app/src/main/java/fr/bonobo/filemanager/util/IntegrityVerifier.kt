package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase

/** Reopen streams; bounded buffers. Null children means a regular file, never an absent node. */
object IntegrityVerifier {
    data class Child(val name: String, val path: String)
    interface Access {
        fun children(path: String): List<Child>?
        fun open(path: String): InputStream
        fun identity(path: String): String = path
    }
    suspend fun fingerprint(access: Access, path: String): String {
        val visited = mutableSetOf<String>()
        suspend fun hash(node: String): ByteArray {
            currentCoroutineContext().ensureActive()
            check(visited.add(access.identity(node))) { "Cycle ou alias dans la source : vérification refusée" }
            val digest = MessageDigest.getInstance("SHA-256")
            val children = access.children(node)
            if (children == null) {
                digest.update(0.toByte())
                access.open(node).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
            } else {
                digest.update(1.toByte())
                require(children.map { it.name }.distinct().size == children.size) { "Noms ambigus : vérification refusée" }
                for (child in children.sortedBy { it.name }) {
                    require(child.name.isNotEmpty() && child.name !in listOf(".", "..") && child.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Nom invalide dans l'arborescence" }
                    val name = child.name.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.size).array())
                    digest.update(name)
                    digest.update(hash(child.path))
                }
                check(access.children(node)?.sortedBy { it.name } == children.sortedBy { it.name }) { "Arborescence modifiée pendant la vérification" }
            }
            return digest.digest()
        }
        return try { hash(path).joinToString("") { "%02x".format(it) } }
        catch (e: Exception) { Diagnostic.event(Event.SHA256, Result.ERROR); throw e }
    }

    suspend fun copyThenDelete(source: Access, sourcePath: String, destination: Access,
        destinationPath: String, copy: suspend () -> Unit, delete: suspend () -> Unit, deleting: Boolean = true) {
        TransferEvents.emit(TransferEvents.Event(TransferPhase.PREPARING))
        val operation = Diagnostic.operation()
        fun observedMatch(matches: Boolean): Boolean {
            Diagnostic.event(Event.SHA256, if (matches) Result.MATCH else Result.MISMATCH, operation)
            return matches
        }
        val before = fingerprint(source, sourcePath)
        copy()
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
        check(observedMatch(fingerprint(destination, destinationPath) == before)) { "Intégrité de destination non confirmée : source conservée" }
        check(observedMatch(fingerprint(source, sourcePath) == before)) { "Source modifiée : suppression refusée" }
        check(observedMatch(fingerprint(destination, destinationPath) == before)) { "Destination modifiée avant suppression : source conservée" }
        currentCoroutineContext().ensureActive()
        if (deleting) TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
        currentCoroutineContext().ensureActive()
        delete()
    }
}
