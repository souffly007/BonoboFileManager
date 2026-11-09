package fr.bonobo.filemanager.domain.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One application-wide job. Cancellation becomes final only after motor/resource cleanup returns. */
class TransferCoordinator(private val scope: CoroutineScope,
    private val persist: (TransferState) -> Unit = {}, initial: TransferState = TransferState(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val finished = java.util.concurrent.ConcurrentHashMap<String, TransferState>()
    fun result(id: String): TransferState? = finished[id]
    @Synchronized fun submit(request: TransferRequest, engine: TransferEngine): Job? {
        if (mutable.value.active || job?.isCompleted == false || request.sources.isEmpty()) return null
        mutable.value = TransferState(id = UUID.randomUUID().toString(), active = true,
            phase = TransferPhase.PREPARING, operation = request.operation,
            destination = request.destination.path, totalRoots = request.sources.size)
        val work = scope.launch(start = CoroutineStart.LAZY) { run(request, engine) }
        work.invokeOnCompletion {
            synchronized(this) {
                if (mutable.value.active && mutable.value.cancelRequested) {
                    mutable.value = mutable.value.copy(active = false, phase = TransferPhase.CANCELLED,
                        notice = "Opération arrêtée avant démarrage ; aucune reprise automatique.")
                    finished[mutable.value.id] = mutable.value
                }
            }
        }
        job = work
        work.start()
        return work
    }
    @Synchronized fun cancel() {
        if (!mutable.value.active) return
        mutable.value = mutable.value.copy(cancelRequested = true, phase = TransferPhase.CANCEL_REQUESTED,
            speed = null, etaSeconds = null, notice = "Annulation demandée : attente de l’arrêt réel. Une suppression déjà engagée peut être partielle.")
        job?.cancel()
    }
    @Synchronized fun dismiss() {
        if (!mutable.value.active && mutable.value.id.isNotEmpty()) {
            val acknowledged = mutable.value.copy(acknowledged = true)
            try {
                persist(acknowledged)
                mutable.value = acknowledged
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(notice = "Acquittement non enregistré : journal inaccessible. Réessayez de fermer le résultat.")
            }
        }
    }
    private fun set(state: TransferState) = synchronized(this) {
        mutable.value = if (mutable.value.cancelRequested && state.active)
            state.copy(cancelRequested = true, phase = TransferPhase.CANCEL_REQUESTED, speed = null, etaSeconds = null,
                notice = mutable.value.notice) else state
    }
    private suspend fun run(request: TransferRequest, engine: TransferEngine) {
        var sources = request.sources
        val inventories = mutableMapOf<StorageRef, Inventory>()
        val results = mutableListOf<ItemResult>()
        var current: TransferSource? = null
        val rate = TransferRate(clock)
        var lastUpdate = clock()
        try {
            persist(mutable.value)
            sources = engine.normalize(sources)
            set(mutable.value.copy(totalRoots = sources.size))
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                set(mutable.value.copy(currentName = source.name, currentSource = source.ref.path))
                try { inventories[source.ref] = engine.inspect(source) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    results += ItemResult(source, ItemStatus.FAILED, e.message ?: "Préparation impossible")
                }
            }
            val allKnown = inventories.size == sources.size
            val totalFiles = if (allKnown) inventories.values.fold(0L) { n, i -> Math.addExact(n, i.files) } else null
            val totalBytes = if (allKnown && inventories.values.all { it.bytes != null })
                inventories.values.fold(0L) { n, i -> Math.addExact(n, i.bytes!!) } else null
            set(mutable.value.copy(totalFiles = totalFiles, totalBytes = totalBytes, results = results.toList(), processedRoots = results.size))
            for (source in sources) {
                if (results.any { it.source.ref == source.ref }) continue
                currentCoroutineContext().ensureActive()
                current = source
                set(mutable.value.copy(phase = TransferPhase.PREPARING, currentName = source.name,
                    currentSource = source.ref.path, fileBytes = 0, fileTotal = null, speed = null, etaSeconds = null))
                persist(mutable.value)
                var reportedFiles = 0L
                val beforeBytes = mutable.value.bytes
                val events = TransferEvents { event ->
                    if (event.fileStart) reportedFiles = Math.addExact(reportedFiles, 1)
                    val previous = pending ?: mutable.value
                    if (event.phase != TransferPhase.COPYING || previous.phase != event.phase) rate.reset()
                    val bytes = Math.addExact(previous.bytes, event.bytesDelta.coerceAtLeast(0))
                    val (speed, eta) = if (event.phase == TransferPhase.COPYING)
                        rate.sample(bytes, previous.totalBytes?.minus(bytes)) else null to null
                    val updated = previous.copy(phase = event.phase,
                        totalBytes = previous.totalBytes?.takeIf { bytes <= it },
                        currentName = event.name ?: previous.currentName,
                        currentSource = event.path ?: previous.currentSource, bytes = bytes,
                        fileBytes = event.fileBytes ?: if (event.phase == TransferPhase.COPYING && event.path != null) 0 else previous.fileBytes,
                        fileTotal = if (event.path != null) event.fileTotal else previous.fileTotal,
                        speed = speed, etaSeconds = eta)
                    // Always keep exact counters; StateFlow emission is throttled for chunk events.
                    pending = updated
                    val now = clock()
                    if (event.bytesDelta == 0L || now - lastUpdate >= 250) {
                        set(updated); pending = null; lastUpdate = now
                    }
                }
                try {
                    withContext(events) { engine.execute(source, request.destination, request.operation) }
                    flushPending()
                    results += ItemResult(source, ItemStatus.DONE)
                    val inventory = inventories.getValue(source.ref)
                    val actualFiles = if (reportedFiles > 0 || inventory.files == 0L) reportedFiles else inventory.files
                    val changedCount = actualFiles != inventory.files
                    val changedSize = inventory.bytes != null && mutable.value.bytes - beforeBytes != inventory.bytes
                    set(mutable.value.copy(completedFiles = Math.addExact(mutable.value.completedFiles, actualFiles),
                        totalFiles = if (changedCount) null else mutable.value.totalFiles,
                        totalBytes = if (changedSize || changedCount) null else mutable.value.totalBytes))
                } catch (e: Exception) {
                    flushPending()
                    if (e is CancellationException) throw e
                    results += ItemResult(source, ItemStatus.FAILED, e.message ?: "Opération non confirmée")
                }
                current = null
                set(mutable.value.copy(results = results.toList(), processedRoots = results.size, speed = null, etaSeconds = null))
                persist(mutable.value)
            }
            currentCoroutineContext().ensureActive()
            val covered = request.sources.filter { original -> sources.none { it.ref == original.ref } }
            val allDone = results.all { it.status == ItemStatus.DONE }
            covered.forEach { results += ItemResult(it, if (allDone) ItemStatus.DONE else ItemStatus.UNCERTAIN,
                "Source déjà couverte par une racine sélectionnée ; aucune copie supplémentaire.") }
            set(mutable.value.copy(totalRoots = request.sources.size, processedRoots = results.size,
                active = false, phase = if (results.any { it.status != ItemStatus.DONE }) TransferPhase.ERROR else TransferPhase.DONE,
                results = results.toList(), speed = null, etaSeconds = null, notice = "Les éléments terminés ont été vérifiés immédiatement. Persistance après reconnexion non testée. Aucun remplacement automatique."))
        } catch (e: CancellationException) {
            flushPending()
            current?.let { results += ItemResult(it, ItemStatus.UNCERTAIN, "Opération interrompue : contrôlez la destination. Aucune suppression supplémentaire ne sera lancée. ${e.message.orEmpty()}") }
            sources.filter { source -> results.none { it.source.ref == source.ref } }.forEach {
                results += ItemResult(it, ItemStatus.CANCELLED, "Non démarré ; source conservée")
            }
            set(mutable.value.copy(active = false, phase = TransferPhase.CANCELLED, results = results.toList(), processedRoots = results.count { it.status != ItemStatus.CANCELLED }, speed = null, etaSeconds = null,
                notice = "Opération arrêtée. ${e.message.orEmpty()} Les éléments déjà terminés restent terminés ; aucune reprise automatique."))
        } catch (e: Exception) {
            flushPending()
            set(mutable.value.copy(active = false, phase = TransferPhase.ERROR, results = results.toList(), speed = null, etaSeconds = null,
                notice = "Opération arrêtée : ${e.message}. Contrôlez les éventuelles destinations partielles."))
        } finally {
            synchronized(this) {
            try { persist(mutable.value) } catch (_: Exception) {
                set(mutable.value.copy(active = false, phase = TransferPhase.ERROR, notice = "Journal inaccessible : résultat non persisté ; contrôlez les destinations."))
            }
            finished[mutable.value.id] = mutable.value
            if (finished.size > 8) finished.keys.firstOrNull { it != mutable.value.id }?.let { finished.remove(it) }
            }
        }
    }
    private var pending: TransferState? = null
    private fun flushPending() { pending?.let { set(it) }; pending = null }
}
