package fr.bonobo.filemanager.util

import android.content.Context
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Partial files left on a key that was removed during a copy are recorded as « deferred »
 * (volume UUID + path + operation id). When that volume is mounted again, those receipts — and
 * only those — are deleted, verified, and the result is logged (PENDING_CLEANUP_DONE).
 * Same treatment for a transfer interrupted because the app was killed ([adoptInterrupted]).
 */
object PendingCleanup {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    /** Incremented whenever receipts change, so the UI can refresh its « fichiers partiels » list. */
    val revision = changes.asStateFlow()
    fun notifyChanged() { changes.value = changes.value + 1 }

    /** op id of the copy that created the receipts (null when the journal is off, like [Diagnostic.operation]). */
    fun original(id: String): java.util.UUID? =
        Diagnostic.operation()?.let { runCatching { java.util.UUID.fromString(id) }.getOrNull() ?: it }

    data class Summary(val deleted: Int, val leftover: Int, val unknown: Int)

    /**
     * Clean deferred receipts whose volume is mounted again, after adopting those of transfers
     * interrupted by a killed app ([adoptInterrupted]). Never touches running operations.
     */
    suspend fun runDeferred(context: Context): Summary = mutex.withLock {
        val directory = SafFiles.artifactDirectory(context)
        val mounted: (String?) -> Boolean = { UsbVolumes.isMounted(context, it) }
        runCatching { adoptInterrupted(directory) }
        val summary = cleanDeferred(directory, SafFiles(context).cleanupAccess(), mounted) { ready ->
            val kinds = ready.map { runCatching { UsbVolumes.storage(context, it.parent) }.getOrDefault(Storage.UNKNOWN) }
            if (Storage.USB in kinds) Storage.USB else kinds.firstOrNull() ?: Storage.UNKNOWN
        }
        if (summary.deleted + summary.leftover + summary.unknown > 0) notifyChanged()
        summary
    }

    /**
     * App killed (or phone restarted) during a transfer: its receipts are still « en cours » (written
     * before the .bonobo-part was created) but no operation of this process runs them. They are
     * treated exactly like a key removed during the copy: every receipt becomes deferred (PENDING_CLEANUP,
     * op= of the interrupted copy) and is cleaned as soon as its volume is observable — immediately for
     * the internal storage or a key still plugged in. Pure (no Android), unit-tested.
     * @return number of receipts adopted.
     */
    fun adoptInterrupted(directory: java.io.File): Int {
        var adopted = 0
        for (id in OperationArtifacts.interrupted(directory)) {
            val operation = runCatching { OperationArtifacts.open(directory, id) }.getOrNull() ?: continue
            val entries = operation.entries()
            entries.filter { !it.deferred }.forEach { operation.record(it.copy(deferred = true)) }
            operation.end()
            if (entries.isEmpty()) continue
            adopted += entries.size
            Diagnostic.detail(Event.PENDING_CLEANUP, Result.START, original(id), Storage.UNKNOWN,
                mode = Mode.CLEANUP, expected = entries.size.toLong())
        }
        return adopted
    }

    /**
     * Core of [runDeferred], unit-tested with a local [ArtifactCleanup.Access]. A receipt is ready when
     * it is deferred and its volume is mounted (null = internal storage / non-USB provider: always).
     */
    fun cleanDeferred(directory: java.io.File, access: ArtifactCleanup.Access, mounted: (String?) -> Boolean,
        storageOf: (List<OperationArtifacts.Entry>) -> Storage = { Storage.UNKNOWN }): Summary {
        val ids = runCatching { OperationArtifacts.finishedIds(directory) }.getOrDefault(emptyList())
        var deleted = 0; var leftover = 0; var unknown = 0
        for (id in ids) {
            val operation = runCatching { OperationArtifacts.open(directory, id) }.getOrNull() ?: continue
            // Never an operation still live elsewhere (adopted ones were ended by adoptInterrupted).
            if (operation.isLive) continue
            val ready = operation.entries().filter { it.deferred && mounted(it.volume) }
            if (ready.isEmpty()) continue
            val storage = storageOf(ready)
            // Same op= as the interrupted copy (receipt id = journal op id), so both can be correlated.
            val diagnostic = original(id)
            val report = runCatching {
                // After a remount / restart the copy is over: what is left beside our parts are files verified
                // before the interruption (same rule as before: a folder without .bonobo-part is kept).
                ArtifactCleanup.execute(operation, access, { it in ready }, diagnostic, storage,
                    verified = { node -> !node.name.endsWith(OperationArtifacts.PART_SUFFIX) })
            }.getOrNull() ?: continue
            for ((entry, outcome) in report.outcomes) when (outcome) {
                ArtifactCleanup.Outcome.DELETED, ArtifactCleanup.Outcome.KEPT -> deleted++
                ArtifactCleanup.Outcome.UNKNOWN -> unknown++ // still not observable: stays deferred
                ArtifactCleanup.Outcome.LEFTOVER -> {
                    // Volume present but deletion impossible: hand over to the user (no more automatic retries).
                    operation.record(entry.copy(deferred = false)); leftover++
                }
            }
            Diagnostic.detail(Event.PENDING_CLEANUP_DONE, when {
                report.outcomes.values.all { it == ArtifactCleanup.Outcome.DELETED } -> Result.DELETED
                report.overall == ArtifactCleanup.Outcome.DELETED -> Result.KEPT_VERIFIED
                report.outcomes.values.any { it == ArtifactCleanup.Outcome.UNKNOWN } -> Result.UNKNOWN
                else -> Result.LEFTOVER
            }, diagnostic, storage, mode = Mode.CLEANUP, expected = ready.size.toLong(),
                transferred = report.count(ArtifactCleanup.Outcome.DELETED).toLong())
        }
        return Summary(deleted, leftover, unknown)
    }

    /** « Supprimer » from the list of partial files: one recorded document, verified. */
    suspend fun cleanNow(context: Context, entry: OperationArtifacts.Entry): ArtifactCleanup.Outcome = mutex.withLock {
        val directory = SafFiles.artifactDirectory(context)
        val saf = SafFiles(context)
        var outcome = ArtifactCleanup.Outcome.UNKNOWN
        for (id in OperationArtifacts.finishedIds(directory)) {
            val operation = OperationArtifacts.open(directory, id)
            val stored = operation.entries().firstOrNull { it.path == entry.path } ?: continue
            // A folder is removed only if empty; delete its recorded content first.
            val selected = operation.entries().filter { it == stored || it.parent == stored.path }
            val report = ArtifactCleanup.execute(operation, saf.cleanupAccess(), { it in selected },
                original(id), UsbVolumes.storage(context, stored.parent))
            outcome = report.outcomes[stored] ?: ArtifactCleanup.Outcome.UNKNOWN
            if (outcome == ArtifactCleanup.Outcome.UNKNOWN) operation.record(stored.copy(deferred = stored.volume != null))
        }
        notifyChanged()
        outcome
    }
}
