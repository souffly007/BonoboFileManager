package fr.bonobo.filemanager.data.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.domain.transfer.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

@Singleton
class TransferManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val journal = TransferJournal(File(context.filesDir, "transfers/latest.properties"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = TransferCoordinator(scope, journal::write, journal.read())
    val state = coordinator.state
    /** Last request kept in memory only (never persisted): allows « Réessayer » on the result card. */
    /** [usbVolumes]: UUIDs of the USB keys involved (sources and destination), noted at start, while mounted. */
    private data class Last(val stateId: String, val request: TransferRequest, val engine: TransferEngine,
        val usbVolumes: Set<String> = emptySet())
    @Volatile private var last: Last? = null

    fun start(request: TransferRequest, engine: TransferEngine): Job? {
        check(!fr.bonobo.filemanager.service.RemoteDownloadService.state.value.active) { "Un téléchargement distant est déjà en cours ; attendez sa fin." }
        // Before submitting: the keys are mounted now; once unplugged their path may no longer resolve.
        val volumes = usbVolumes(request)
        val job = coordinator.submit(request, engine) ?: return null
        last = Last(coordinator.state.value.id, request, engine, volumes)
        try {
            context.startForegroundService(android.content.Intent(context, fr.bonobo.filemanager.service.ManagedTransferService::class.java))
        } catch (e: Exception) {
            coordinator.cancel()
            throw IllegalStateException("Service de transfert indisponible ; arrêt demandé. ${e.message}", e)
        }
        return job
    }

    /** Elements of the displayed result that can be attempted again. */
    private fun retryable(state: TransferState): List<TransferSource> = state.results
        .filter { it.status != ItemStatus.DONE && it.status != ItemStatus.SKIPPED }
        .map { it.source }

    fun canRetry(state: TransferState): Boolean {
        val previous = last ?: return false
        return !state.active && previous.stateId == state.id && retryable(state).isNotEmpty()
    }

    /**
     * Why « Réessayer » is temporarily unavailable: a USB key of the operation (destination, or source of a
     * move / mise à la corbeille) is not mounted.
     * The button comes back when the volume is mounted (same signal as USB_VOLUME_WAIT OK).
     */
    fun retryBlocked(state: TransferState): String? {
        if (!canRetry(state)) return null
        val previous = last ?: return null
        return blockReason(previous.usbVolumes) { fr.bonobo.filemanager.util.UsbVolumes.isMounted(context, it) }
    }

    private fun usbVolumes(request: TransferRequest): Set<String> =
        (request.sources.map { it.ref } + request.destination + request.related).filter { it !is StorageRef.Remote }.mapNotNull { ref ->
            runCatching { fr.bonobo.filemanager.util.UsbVolumes.forPath(context, ref.path) }.getOrNull()
                ?.takeIf { fr.bonobo.filemanager.util.UsbVolumes.isUsb(it) }?.uuid
        }.toSet()

    companion object {
        /**
         * « Réessayer » waits for every USB key of the operation — destination of a copy, or source of a
         * move / mise à la corbeille — to be mounted again. Pure, unit-tested.
         */
        fun blockReason(usbVolumes: Set<String>, mounted: (String) -> Boolean): String? =
            if (usbVolumes.any { !mounted(it) }) "Rebranchez la clé USB : « Réessayer » sera disponible dès qu’elle sera accessible." else null
    }

    /**
     * Relaunch the failed / cancelled / interrupted elements. Folders are merged: files already
     * copied (and verified) are skipped, so a retry acts as a simple resume. Atomic writes guarantee
     * that no partial file carries a final name, so files keep their original conflict policy.
     * A folder copied with « Renommer » is copied again under a new name (never merged silently).
     */
    fun retry(): Job? {
        val state = coordinator.state.value
        val previous = last ?: return null
        if (!canRetry(state) || retryBlocked(state) != null) return null
        val sources = retryable(state).map { source ->
            if (source.directory && source.conflict == ConflictAction.ASK)
                source.copy(conflict = ConflictAction.MERGE,
                    compareHash = source.compareHash || previous.request.operation == TransferOperation.MOVE)
            else source
        }
        return start(previous.request.copy(sources = sources), previous.engine)
    }

    fun result(id: String): TransferState? = coordinator.result(id)
    fun cancel() = coordinator.cancel()
    fun dismiss() = coordinator.dismiss()
}
