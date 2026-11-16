package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage


/**
 * Delete only matching receipts, in reverse creation order, and only empty directories.
 *
 * Every deletion is followed by an observation of the parent (as DELETE_VERIFY does). Three outcomes:
 *  - DELETED : the parent was readable on a mounted volume and the document is confirmed absent;
 *  - LEFTOVER: the volume is present but the document is still there (refusal, foreign content…);
 *  - UNKNOWN : the volume / parent is unavailable — absence cannot be observed.
 * An empty listing of an unmounted mount point is NOT evidence of deletion.
 *
 * The document is found through the receipt ([Access.matches]: document id returned at creation,
 * or canonical path), never through another document carrying the same name. For a partial file
 * (.bonobo-part) size and date are not frozen at creation: any size ≤ bytes sent is accepted
 * (data still in the cache when the key was pulled out is simply missing); deletion is refused
 * only if the document grew, or was modified, after the interruption.
 */
object ArtifactCleanup {
    data class Node(val path: String, val name: String, val directory: Boolean, val identity: String,
        val size: Long? = null, val modified: Long? = null)
    /**
     * KEPT: a folder created by the operation that holds only complete, verified files (no
     * .bonobo-part) — the expected result of an interrupted copy, kept on purpose (KEPT_VERIFIED).
     * Its receipt is forgotten; it counts as clean.
     */
    enum class Outcome { DELETED, LEFTOVER, UNKNOWN, KEPT }
    data class Report(val outcomes: Map<OperationArtifacts.Entry, Outcome>) {
        val remaining: List<OperationArtifacts.Entry> get() = outcomes.filterValues { it != Outcome.DELETED && it != Outcome.KEPT }.keys.toList()
        val overall: Outcome get() = when {
            outcomes.values.all { it == Outcome.DELETED || it == Outcome.KEPT } -> Outcome.DELETED
            outcomes.values.any { it == Outcome.UNKNOWN } -> Outcome.UNKNOWN
            else -> Outcome.LEFTOVER
        }
        fun count(outcome: Outcome) = outcomes.values.count { it == outcome }
    }
    interface Access {
        fun list(parent: String): List<Node>
        fun delete(entry: OperationArtifacts.Entry)
        fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry): Boolean = false
        /** Is the parent observable on a mounted volume? Default: it can be listed. */
        fun available(entry: OperationArtifacts.Entry): Boolean = runCatching { list(entry.parent); true }.getOrDefault(false)
        /** Is [node] the very document this receipt describes? Default: same recorded identity. */
        fun matches(entry: OperationArtifacts.Entry, node: Node): Boolean = node.identity == entry.identity
    }

    private val cleanedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    /**
     * Parent folders of the documents removed (or kept) by a cleanup that ended DELETED / KEPT_VERIFIED,
     * emitted right after its CLEANUP line, so the displayed folder can be refreshed at once (the
     * .bonobo-part must not stay visible until the end of the operation).
     */
    val cleaned: kotlinx.coroutines.flow.SharedFlow<Set<String>> = cleanedFlow

    private fun normalized(path: String): String =
        if (SafFiles.isSaf(path)) path.trimEnd('/') else runCatching { java.io.File(path).canonicalPath }.getOrDefault(path.trimEnd('/'))

    /** Is [displayed] (folder shown in a panel) one of the [parents] just cleaned? Pure, unit-tested. */
    fun concerns(displayed: String?, parents: Set<String>): Boolean {
        if (displayed.isNullOrBlank()) return false
        val shown = normalized(displayed)
        return parents.any { normalized(it) == shown }
    }

    /** FAT stores modification times with a 2 s granularity. */
    const val MTIME_TOLERANCE_MS = FileIdentity.MTIME_TOLERANCE_MS

    private class Kept : Exception()
    private class Leftover(val reason: Result, message: String,
        val expected: Long? = null, val found: Long? = null) : Exception(message)

    fun run(operation: OperationArtifacts, access: Access,
        select: (OperationArtifacts.Entry) -> Boolean = { true }): List<OperationArtifacts.Entry> =
        execute(operation, access, select).remaining

    /** Why [node] must not be deleted for this receipt; null when it is ours. Pure, unit-tested. */
    fun mismatch(entry: OperationArtifacts.Entry, node: Node): Pair<Result, Pair<Long?, Long?>>? {
        if (node.name != entry.name) return Result.NAME_MISMATCH to (entry.name.length.toLong() to node.name.length.toLong())
        // The node was already selected by its document id / path (Access.matches): compare the rest
        // with the shared identity rules, in « partial » mode for our .bonobo-part files.
        val expected = FileIdentity(directory = entry.directory,
            size = if (entry.directory) null else entry.bytesSent, modified = if (entry.directory) null else entry.modifiedAt)
        val found = FileIdentity(directory = node.directory, size = node.size, modified = node.modified)
        val mismatch = FileIdentity.compare(expected, found, partial = entry.staging || entry.bytesSent != null) ?: return null
        return mismatch.result(partial = true) to (mismatch.expected to mismatch.found)
    }

    /**
     * @param diagnosticOperation op id of the copy concerned, so CLEANUP lines can be correlated.
     * @param verified is this child of a created folder a complete file verified by the operation?
     *   A folder holding only such files is KEPT (KEPT_VERIFIED), never reported as a leftover.
     */
    fun execute(operation: OperationArtifacts, access: Access,
        select: (OperationArtifacts.Entry) -> Boolean = { true },
        diagnosticOperation: java.util.UUID? = null, storage: Storage = Storage.UNKNOWN,
        verified: (Node) -> Boolean = { false }): Report {
        val kept = mutableListOf<OperationArtifacts.Entry>()
        val op = diagnosticOperation ?: Diagnostic.operation()
        val started = System.nanoTime()
        val outcomes = linkedMapOf<OperationArtifacts.Entry, Outcome>()
        for (entry in operation.entries().filter(select).asReversed()) {
            val outcome = try {
                if (!access.available(entry)) throw Leftover(Result.VOLUME_ABSENT, "Volume ou dossier parent indisponible")
                // A receipt without confirmed identity is only trusted for our own reserved .bonobo-part names
                // (they embed the operation id, so no other document can carry them).
                val byName = !entry.owned && entry.staging && entry.name.endsWith(OperationArtifacts.PART_SUFFIX)
                if (!entry.owned && !byName) throw Leftover(Result.INDETERMINATE, "Création non confirmée")
                val siblings = access.list(entry.parent)
                val node = if (byName) siblings.singleOrNull { it.name == entry.name && !it.directory }
                    else siblings.firstOrNull { access.matches(entry, it) }
                if (node != null) {
                    mismatch(entry, node)?.let { (reason, values) ->
                        throw Leftover(reason, "Document différent du reçu", values.first, values.second)
                    }
                    if (entry.directory) {
                        if (!access.supportsEmptyDirectoryDelete(entry)) throw Leftover(Result.REFUSED, "Suppression de dossier non prise en charge")
                        val content = access.list(entry.path)
                        if (content.isNotEmpty()) {
                            // Only complete files verified by this operation (or folders kept for that reason).
                            if (content.all { child -> !child.name.endsWith(OperationArtifacts.PART_SUFFIX) &&
                                    (verified(child) || kept.any { access.matches(it, child) }) }) throw Kept()
                            throw Leftover(Result.NOT_EMPTY, "Dossier non vide")
                        }
                    }
                    access.delete(entry.copy(path = node.path))
                    if (!access.available(entry)) throw Leftover(Result.VOLUME_ABSENT, "Volume retiré pendant la suppression")
                    val after = access.list(entry.parent)
                    if (after.any { it.identity == node.identity || access.matches(entry, it) })
                        throw Leftover(Result.REFUSED, "Toujours présent après suppression")
                } else if (siblings.any { it.name == entry.name }) {
                    // Our document is gone and another one carries its name: never delete it.
                    throw Leftover(Result.DOCUMENT_MISMATCH, "Un autre document porte ce nom")
                }
                // Our document is absent.
                operation.forget(entry)
                Outcome.DELETED
            } catch (_: Kept) {
                operation.forget(entry); kept += entry
                Outcome.KEPT
            } catch (e: Leftover) {
                Diagnostic.detail(Event.PARTIAL_LEFTOVER, e.reason, op, storage,
                    if (entry.directory) Kind.DIRECTORY else Kind.FILE, Mode.CLEANUP,
                    expected = e.expected, transferred = e.found)
                if (e.reason == Result.VOLUME_ABSENT) Outcome.UNKNOWN else Outcome.LEFTOVER
            } catch (e: Exception) {
                // Never swallow silently: classify the reason in the journal (closed vocabulary).
                val gone = !runCatching { access.available(entry) }.getOrDefault(false)
                val reason = when {
                    gone -> Result.VOLUME_ABSENT
                    generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } -> Result.PERMISSION_DENIED
                    e is java.io.FileNotFoundException -> Result.NOT_FOUND
                    e is java.io.IOException -> Result.IO_ERROR
                    else -> Result.ERROR
                }
                Diagnostic.detail(Event.PARTIAL_LEFTOVER, reason, op, storage,
                    if (entry.directory) Kind.DIRECTORY else Kind.FILE, Mode.CLEANUP)
                if (gone) Outcome.UNKNOWN else Outcome.LEFTOVER
            }
            outcomes[entry] = outcome
        }
        val report = Report(outcomes)
        // One single CLEANUP line per cleanup, carrying the copy's op id.
        Diagnostic.detail(Event.CLEANUP, when (report.overall) {
            Outcome.DELETED, Outcome.KEPT -> if (report.count(Outcome.KEPT) > 0) Result.KEPT_VERIFIED else Result.DELETED
            Outcome.LEFTOVER -> Result.LEFTOVER
            Outcome.UNKNOWN -> Result.UNKNOWN
        }, op, storage, kind = when {
            outcomes.isEmpty() -> Kind.UNKNOWN
            outcomes.keys.any { it.directory } -> Kind.DIRECTORY
            else -> Kind.FILE
        }, mode = Mode.CLEANUP, durationMs = (System.nanoTime() - started) / 1_000_000,
            expected = outcomes.size.toLong(), transferred = report.count(Outcome.DELETED).toLong())
        if (outcomes.isNotEmpty() && report.overall == Outcome.DELETED)
            cleanedFlow.tryEmit(outcomes.keys.map { it.parent }.toSet())
        return report
    }
}
