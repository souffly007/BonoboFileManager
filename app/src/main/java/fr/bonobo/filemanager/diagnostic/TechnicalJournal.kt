package fr.bonobo.filemanager.diagnostic

import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Closed vocabulary: no user-controlled strings, exceptions or hashes enter the journal. */
class TechnicalJournal(private val directory: File, private val limit: Int = 1_000_000) {
    enum class Event { ENABLED, COPY_START, COPY_END, INPUT_OPEN, OUTPUT_OPEN, STREAMS_CLOSED,
        SHA256, PUBLICATION, RENAME, DELETE, DISAPPEARANCE, MOVE, SOURCE_RETAINED,
        APP_RESUMED, CANCEL, CLEANUP, USB_DETECTED, USB_RECONNECTED, USB_REMOVED, SAF_PERMISSION, SAF_FAILURE,
        CREATE_RESULT, DESTINATION_CONFLICT, DELETE_REQUEST, DELETE_START, DELETE_PROVIDER_RESULT, DELETE_VERIFY, DELETE_END,
        NAV_ROOT, NAV_PERMISSION, NAV_OPEN, NAV_READ, NAV_PROVIDER_FAILURE, SAF_RESELECT }
    enum class Result { START, OK, ERROR, MATCH, MISMATCH, CANCELLED, REFUSED, INDETERMINATE, PERMISSION_DENIED, NO_PERSISTED_GRANT, NOT_FOUND, IO_ERROR, PROVIDER_REJECTED, PROVIDER_UNAVAILABLE, OPEN_FAILED, REFERENCE_INVALID, CONFLICT }
    enum class Storage { LOCAL, SAF, OTHER, UNKNOWN }
    enum class Kind { FILE, DIRECTORY, UNKNOWN }
    enum class Mode { PERMANENT, TRASH, CLEANUP, UNKNOWN }
    enum class Phase { REQUEST, CREATE, OPEN, READ, WRITE, CLOSE, PROVIDER, VERIFY, FINISH, UNKNOWN }
    @Volatile var enabled = false
        private set
    @Volatile private var generation = 0L
    init { require(limit >= 512) }
    private val gate = Any()
    private val disk = Any()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(256), { r -> Thread(r, "technical-diagnostic").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())
    fun setEnabled(value: Boolean) = synchronized(gate) { enabled = value; generation++; executor.queue.clear() }
    fun operation(): UUID? = if (enabled) UUID.randomUUID() else null
    fun record(event: Event, result: Result = Result.OK, operation: UUID? = null,
        expected: Long? = null, transferred: Long? = null, durationMs: Long? = null) {
        recordLine(event, result, operation, expected, transferred, durationMs, "")
    }
    fun recordDetail(event: Event, result: Result, operation: UUID? = null,
        storage: Storage = Storage.UNKNOWN, kind: Kind = Kind.UNKNOWN, mode: Mode = Mode.UNKNOWN,
        phase: Phase = Phase.UNKNOWN, durationMs: Long? = null) {
        recordLine(event, result, operation, null, null, durationMs,
            " storage=${storage.name} kind=${kind.name} mode=${mode.name} phase=${phase.name}")
    }
    private fun recordLine(event: Event, result: Result, operation: UUID?,
        expected: Long?, transferred: Long?, durationMs: Long?, suffix: String) {
        try {
            synchronized(gate) {
                if (!enabled) return
                val epoch = generation
                val line = "${System.currentTimeMillis()} ${event.name} ${result.name} op=${operation ?: "-"} expected=${expected ?: -1} transferred=${transferred ?: -1} durationMs=${durationMs ?: -1}$suffix\n"
                executor.execute { synchronized(disk) {
                    if (enabled && epoch == generation) runCatching {
                        directory.mkdirs()
                        val current = File(directory, "current.log")
                        val previous = File(directory, "previous.log")
                        val bytes = line.toByteArray(Charsets.US_ASCII)
                        if (current.length() + bytes.size > limit / 2) {
                            previous.delete()
                            check(!current.exists() || current.renameTo(previous))
                        }
                        check(bytes.size <= limit / 2)
                        current.appendBytes(bytes)
                    }
                } }
            }
        } catch (_: Exception) { /* Diagnostics must never change an operation's result. */ }
    }
    fun clear(): Boolean = synchronized(disk) {
        synchronized(gate) { generation++; executor.queue.clear() }
        runCatching { listOf("current.log", "previous.log").forEach {
            val file = File(directory, it); check(!file.exists() || file.delete())
        } }.isSuccess
    }
    fun export(output: OutputStream): Boolean = synchronized(disk) {
        runCatching { listOf("previous.log", "current.log").forEach {
            val file = File(directory, it)
            if (file.exists()) file.inputStream().use { input -> input.copyTo(output) }
        } }.isSuccess
    }
    internal fun awaitIdle() { val barrier = java.util.concurrent.CountDownLatch(1)
        executor.prestartCoreThread()
        executor.queue.put(Runnable { barrier.countDown() }); check(barrier.await(10, TimeUnit.SECONDS)) }
}

object Diagnostic {
    @Volatile var journal: TechnicalJournal? = null
    fun operation(): UUID? = try { journal?.operation() } catch (_: Exception) { null }
    fun event(event: TechnicalJournal.Event, result: TechnicalJournal.Result = TechnicalJournal.Result.OK,
        operation: UUID? = null, expected: Long? = null, transferred: Long? = null, durationMs: Long? = null) {
        try { journal?.record(event, result, operation, expected, transferred, durationMs) } catch (_: Exception) { }
    }
    fun detail(event: TechnicalJournal.Event, result: TechnicalJournal.Result,
        operation: UUID? = null, storage: TechnicalJournal.Storage = TechnicalJournal.Storage.UNKNOWN,
        kind: TechnicalJournal.Kind = TechnicalJournal.Kind.UNKNOWN,
        mode: TechnicalJournal.Mode = TechnicalJournal.Mode.UNKNOWN,
        phase: TechnicalJournal.Phase = TechnicalJournal.Phase.UNKNOWN, durationMs: Long? = null) {
        try { journal?.recordDetail(event, result, operation, storage, kind, mode, phase, durationMs) } catch (_: Exception) { }
    }
}
