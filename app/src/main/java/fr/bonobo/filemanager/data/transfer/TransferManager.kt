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
    fun start(request: TransferRequest, engine: TransferEngine): Job? {
        check(!fr.bonobo.filemanager.service.RemoteDownloadService.state.value.active) { "Un téléchargement distant est déjà en cours ; attendez sa fin." }
        val job = coordinator.submit(request, engine) ?: return null
        try {
            context.startForegroundService(android.content.Intent(context, fr.bonobo.filemanager.service.ManagedTransferService::class.java))
        } catch (e: Exception) {
            coordinator.cancel()
            throw IllegalStateException("Service de transfert indisponible ; arrêt demandé. ${e.message}", e)
        }
        return job
    }
    fun result(id: String): TransferState? = coordinator.result(id)
    fun cancel() = coordinator.cancel()
    fun dismiss() = coordinator.dismiss()
}
