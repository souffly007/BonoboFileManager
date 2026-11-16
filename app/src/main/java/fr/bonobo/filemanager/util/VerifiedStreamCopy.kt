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
    /** COPY_PROGRESS every 10 % of the file, or every 100 MiB, whichever comes first. */
    const val PROGRESS_BYTES = 100L * 1024 * 1024

    /**
     * Verification progress for the UI (« Vérification : 45 % »): reports the bytes read at most
     * every 1 % (or 4 MiB when the size is unknown), so the state flow is not flooded.
     */
    class ProgressThrottle(total: Long?, private val report: ((Long) -> Unit)?) {
        private val step = if (total != null && total > 0) maxOf(64L * 1024, total / 100) else 4L * 1024 * 1024
        private var read = 0L
        private var next = step
        fun add(count: Long) {
            read += count
            if (report != null && read >= next) { report.invoke(read); while (next <= read) next += step }
        }
    }

    data class Evidence(val bytes: Long, val sha256: ByteArray, val diagnosticOperation: java.util.UUID? = null)

    /**
     * @param operation diagnostic id shared by every event of the same copy (COPY_*, SHA256, CLEANUP).
     */
    suspend fun write(openInput: () -> InputStream, openOutput: () -> OutputStream,
        expected: Long?, sync: (OutputStream) -> Unit = {}, progress: (Long, Int) -> Unit = { _, _ -> },
        operation: java.util.UUID? = null): Evidence {
        val operation = operation ?: Diagnostic.operation()
        val started = System.nanoTime()
        Diagnostic.event(Event.COPY_START, Result.START, operation, expected)
        var copied = 0L
        var closed = false
        val step = if (expected != null && expected > 0) minOf(PROGRESS_BYTES, maxOf(1L, expected / 10)) else PROGRESS_BYTES
        var nextProgress = step
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
                        if (copied >= nextProgress && (expected == null || copied < expected)) {
                            Diagnostic.event(Event.COPY_PROGRESS, Result.OK, operation, expected, copied,
                                (System.nanoTime() - started) / 1_000_000)
                            while (nextProgress <= copied) nextProgress += step
                        }
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
            Diagnostic.event(Event.COPY_END, when {
                e is kotlinx.coroutines.CancellationException -> Result.CANCELLED
                CopyFailures.isNoSpace(e) -> Result.INSUFFICIENT_SPACE
                else -> Result.ERROR
            }, operation, expected, transferred = copied, durationMs = (System.nanoTime() - started) / 1_000_000)
            throw e
        } finally {
            if (!closed) Diagnostic.event(Event.STREAMS_CLOSED, Result.ERROR, operation)
        }
    }

    suspend fun verify(openInput: () -> InputStream, expected: Evidence, advertisedSize: Long?,
        progress: ((Long) -> Unit)? = null) {
        val operation = expected.diagnosticOperation ?: Diagnostic.operation()
        var outcome = Result.ERROR
        try {
            check(advertisedSize == null || advertisedSize == expected.bytes) { "Taille de destination incorrecte" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val throttle = ProgressThrottle(expected.bytes, progress)
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
                    throttle.add(count.toLong())
                }
            }
            Diagnostic.event(Event.STREAMS_CLOSED, operation = operation)
            val matches = total == expected.bytes && digest.digest().contentEquals(expected.sha256)
            outcome = if (matches) Result.MATCH else Result.MISMATCH
            check(matches) {
                "Contenu de destination non confirmé : source conservée"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancellation during verification is not an integrity error.
            outcome = Result.CANCELLED
            throw e
        } finally { Diagnostic.event(Event.SHA256, outcome, operation, expected.bytes) }
    }
}
