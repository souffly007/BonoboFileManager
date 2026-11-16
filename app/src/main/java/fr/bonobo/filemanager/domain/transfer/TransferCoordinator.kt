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
                        notice = "${verb(mutable.value.operation)} annulé${agreement(mutable.value.operation)} avant démarrage.")
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
            speed = null, etaSeconds = null, notice = "Annulation demandée : arrêt et suppression des fichiers partiels en cours.")
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
    private fun verb(operation: TransferOperation) = when (operation) {
        TransferOperation.MOVE -> "Déplacement"
        TransferOperation.DUPLICATE -> "Duplication"
        TransferOperation.COPY -> "Copie"
        TransferOperation.TRASH -> "Mise à la corbeille"
        TransferOperation.RESTORE -> "Restauration"
    }
    /** « Copie annulée » / « Déplacement annulé ». */
    private fun agreement(operation: TransferOperation) = if (operation == TransferOperation.MOVE) "" else "e"
    /** « non copié(s) », « non déplacé(s) », « non mis à la corbeille ». */
    private fun notDone(operation: TransferOperation, count: Int) = when (operation) {
        TransferOperation.TRASH -> "mis à la corbeille"
        TransferOperation.RESTORE -> if (count > 1) "restaurés" else "restauré"
        TransferOperation.MOVE -> if (count > 1) "déplacés" else "déplacé"
        else -> if (count > 1) "copiés" else "copié"
    }

    private fun fatalNotice(failure: TransferFailure, state: TransferState): String = when (failure.kind) {
        FailureKind.DISK_FULL -> {
            val place = if (failure.usb) "Clé USB pleine" else "Stockage de destination plein"
            val written = TransferText.size(state.bytes)
            val total = state.totalBytes?.let { " sur ${TransferText.size(it)}" } ?: ""
            "$place : $written copiés$total. Libérez de l’espace puis réessayez."
        }
        FailureKind.USB_REMOVED -> "La clé USB a été retirée pendant la ${verb(state.operation).lowercase()}. " +
            "Rebranchez-la puis appuyez sur « Réessayer » : les fichiers déjà terminés seront conservés."
        else -> failure.message ?: "${verb(state.operation)} interrompu${agreement(state.operation)}."
    }

    private suspend fun run(request: TransferRequest, engine: TransferEngine) {
        var sources = request.sources
        val inventories = mutableMapOf<StorageRef, Inventory>()
        val results = mutableListOf<ItemResult>()
        var current: TransferSource? = null
        var currentNote: String? = null
        var fatal: TransferFailure? = null
        val failureKinds = mutableListOf<FailureKind>()
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
            // Refuse before the first byte: free space, FAT32 limit…
            try {
                engine.preflight(request.copy(sources = sources.filter { s -> results.none { it.source.ref == s.ref } }), inventories.toMap())
            } catch (e: TransferFailure) {
                val reason = when (e.kind) {
                    FailureKind.INSUFFICIENT_SPACE, FailureKind.DISK_FULL -> "Non démarré : espace insuffisant"
                    FailureKind.FILE_TOO_LARGE -> "Non démarré : fichier trop volumineux pour ce support"
                    else -> "Non démarré"
                }
                sources.filter { source -> results.none { it.source.ref == source.ref } }.forEach {
                    results += ItemResult(it, ItemStatus.NOT_STARTED, "$reason. Rien n’a été écrit.")
                }
                set(mutable.value.copy(active = false, phase = TransferPhase.ERROR, failure = e.kind, failureOnUsb = e.usb,
                    results = results.toList(), processedRoots = results.size, notice = e.message))
                return
            }
            for (source in sources) {
                if (results.any { it.source.ref == source.ref }) continue
                currentCoroutineContext().ensureActive()
                current = source
                currentNote = null
                set(mutable.value.copy(phase = TransferPhase.PREPARING, currentName = source.name,
                    currentSource = source.ref.path, fileBytes = 0, fileTotal = null, speed = null, etaSeconds = null))
                persist(mutable.value)
                var reportedFiles = 0L
                val beforeBytes = mutable.value.bytes
                val events = TransferEvents { event ->
                    if (event.fileStart) reportedFiles = Math.addExact(reportedFiles, 1)
                    event.note?.let { currentNote = it }
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
                    results += ItemResult(source, ItemStatus.DONE, currentNote)
                    val inventory = inventories.getValue(source.ref)
                    val actualFiles = if (reportedFiles > 0 || inventory.files == 0L) reportedFiles else inventory.files
                    val changedCount = actualFiles != inventory.files
                    val changedSize = inventory.bytes != null && mutable.value.bytes - beforeBytes != inventory.bytes
                    set(mutable.value.copy(completedFiles = Math.addExact(mutable.value.completedFiles, actualFiles),
                        totalFiles = if (changedCount) null else mutable.value.totalFiles,
                        totalBytes = if (changedSize || changedCount) null else mutable.value.totalBytes))
                } catch (e: TransferSkipped) {
                    flushPending()
                    results += ItemResult(source, ItemStatus.SKIPPED, e.message)
                    set(mutable.value.copy(totalFiles = null, totalBytes = null))
                } catch (e: Exception) {
                    flushPending()
                    if (e is CancellationException) throw e
                    val typed = e as? TransferFailure
                    results += ItemResult(source, ItemStatus.FAILED, e.message ?: "Opération non confirmée", typed?.technical)
                    failureKinds += typed?.kind ?: FailureKind.OTHER
                    if (typed != null && typed.fatal) {
                        fatal = typed
                        current = null
                        break
                    }
                }
                current = null
                set(mutable.value.copy(results = results.toList(), processedRoots = results.size, speed = null, etaSeconds = null))
                persist(mutable.value)
            }
            fatal?.let { failure ->
                sources.filter { source -> results.none { it.source.ref == source.ref } }.forEach {
                    results += ItemResult(it, ItemStatus.NOT_STARTED, "Non démarré : source conservée.")
                }
                set(mutable.value.copy(totalRoots = sources.size, processedRoots = results.count { it.status != ItemStatus.NOT_STARTED },
                    active = false, phase = TransferPhase.ERROR, failure = failure.kind, failureOnUsb = failure.usb,
                    results = results.toList(), speed = null, etaSeconds = null, notice = fatalNotice(failure, mutable.value)))
                return
            }
            currentCoroutineContext().ensureActive()
            val covered = request.sources.filter { original -> sources.none { it.ref == original.ref } }
            val allDone = results.all { it.status == ItemStatus.DONE || it.status == ItemStatus.SKIPPED }
            covered.forEach { results += ItemResult(it, if (allDone) ItemStatus.DONE else ItemStatus.UNCERTAIN,
                "Déjà inclus dans un dossier sélectionné ; aucune copie supplémentaire.") }
            val errors = results.count { it.status != ItemStatus.DONE && it.status != ItemStatus.SKIPPED }
            val skipped = results.count { it.status == ItemStatus.SKIPPED }
            set(mutable.value.copy(totalRoots = request.sources.size, processedRoots = results.size,
                active = false, phase = if (errors > 0) TransferPhase.ERROR else TransferPhase.DONE,
                failure = when {
                    errors == 0 -> null
                    failureKinds.isNotEmpty() && failureKinds.all { it == FailureKind.CONFLICT } -> FailureKind.CONFLICT
                    else -> FailureKind.OTHER
                },
                results = results.toList(), speed = null, etaSeconds = null,
                notice = when {
                    errors > 0 -> "${TransferText.plural(errors, "élément")} non ${notDone(request.operation, errors)} : touchez « Détails »."
                    skipped > 0 -> "${verb(request.operation)} terminé${agreement(request.operation)} et vérifié${agreement(request.operation)} · " +
                        "${TransferText.plural(skipped, "élément ignoré", "éléments ignorés")}."
                    else -> "${verb(request.operation)} terminé${agreement(request.operation)} et vérifié${agreement(request.operation)}."
                }))
        } catch (e: CancellationException) {
            flushPending()
            current?.let {
                results += ItemResult(it, if (e is CleanCancellation) ItemStatus.CANCELLED else ItemStatus.UNCERTAIN,
                    e.message?.takeIf { m -> m.isNotBlank() } ?: "Interrompu pendant l’écriture : contrôlez la destination.")
            }
            sources.filter { source -> results.none { it.source.ref == source.ref } }.forEach {
                results += ItemResult(it, ItemStatus.CANCELLED, "Non démarré ; source conservée.")
            }
            val uncertain = results.any { it.status == ItemStatus.UNCERTAIN }
            set(mutable.value.copy(active = false, phase = TransferPhase.CANCELLED, results = results.toList(),
                processedRoots = results.count { it.status != ItemStatus.CANCELLED }, speed = null, etaSeconds = null,
                notice = if (!uncertain && e is CleanCancellation && e.keptFiles > 0)
                    "${verb(request.operation)} annulé${agreement(request.operation)} : " +
                        "${TransferText.plural(e.keptFiles, "fichier complet conservé", "fichiers complets conservés")} dans le dossier."
                else "${verb(request.operation)} annulé${agreement(request.operation)}. " +
                    if (uncertain) "Un fichier partiel n’a pas pu être confirmé comme supprimé : touchez « Détails »."
                    else "Les éléments terminés sont conservés ; aucun fichier partiel."))
        } catch (e: Exception) {
            flushPending()
            set(mutable.value.copy(active = false, phase = TransferPhase.ERROR, failure = FailureKind.OTHER,
                results = results.toList(), speed = null, etaSeconds = null,
                notice = "${verb(request.operation)} interrompu${agreement(request.operation)} : ${e.message}"))
        } finally {
            synchronized(this) {
            try { persist(mutable.value) } catch (_: Exception) {
                set(mutable.value.copy(active = false, phase = TransferPhase.ERROR, notice = "Journal inaccessible : résultat non enregistré ; contrôlez la destination."))
            }
            finished[mutable.value.id] = mutable.value
            if (finished.size > 8) finished.keys.firstOrNull { it != mutable.value.id }?.let { finished.remove(it) }
            }
        }
    }
    private var pending: TransferState? = null
    private fun flushPending() { pending?.let { set(it) }; pending = null }
}
