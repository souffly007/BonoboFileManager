package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result


import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owns both streams, including every failure on opening, flushing, syncing and closing. */
object VerifiedStreamCopy {
    data class Evidence(val bytes: Long, val sha256: ByteArray, val diagnosticOperation: java.util.UUID? = null)
    suspend fun write(openInput: () -> InputStream, openOutput: () -> OutputStream,
        expected: Long?, sync: (OutputStream) -> Unit = {}, progress: (Long, Int) -> Unit = { _, _ -> }): Evidence {
        val operation = Diagnostic.operation()
        val started = System.nanoTime()
        Diagnostic.event(Event.COPY_START, Result.START, operation, expected)
        var copied = 0L
        var closed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openInput().use { input ->
                Diagnostic.event(Event.INPUT_OPEN, operation = operation)
                openOutput().use { output ->
                    Diagnostic.event(Event.OUTPUT_OPEN, operation = operation)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied = Math.addExact(copied, count.toLong())
                        progress(copied, count)
                    }
                    check(expected == null || copied == expected) { "Lecture incomplète ou source modifiée" }
                    output.flush()
                    sync(output)
                }
            }
            closed = true
            Diagnostic.event(Event.STREAMS_CLOSED, operation = operation)
            Diagnostic.event(Event.COPY_END, operation = operation, expected = expected, transferred = copied,
                durationMs = (System.nanoTime() - started) / 1_000_000)
            return Evidence(copied, digest.digest(), operation)
        } catch (e: Exception) {
            Diagnostic.event(Event.COPY_END, if (e is kotlinx.coroutines.CancellationException) Result.CANCELLED else Result.ERROR,
                operation, expected, transferred = copied, durationMs = (System.nanoTime() - started) / 1_000_000)
            throw e
        } finally {
            if (!closed) Diagnostic.event(Event.STREAMS_CLOSED, Result.ERROR, operation)
        }
    }

    suspend fun verify(openInput: () -> InputStream, expected: Evidence, advertisedSize: Long?) {
        val operation = expected.diagnosticOperation ?: Diagnostic.operation()
        var outcome = Result.ERROR
        try {
            check(advertisedSize == null || advertisedSize == expected.bytes) { "Taille de destination incorrecte" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            openInput().use { input ->
                Diagnostic.event(Event.INPUT_OPEN, operation = operation)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    total = Math.addExact(total, count.toLong())
                }
            }
            Diagnostic.event(Event.STREAMS_CLOSED, operation = operation)
            val matches = total == expected.bytes && digest.digest().contentEquals(expected.sha256)
            outcome = if (matches) Result.MATCH else Result.MISMATCH
            check(matches) {
                "Contenu de destination non confirmé : source conservée"
            }
        } finally { Diagnostic.event(Event.SHA256, outcome, operation, expected.bytes) }
    }
}
