package fr.bonobo.filemanager.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.bonobo.filemanager.data.transfer.TransferManager
import kotlinx.coroutines.*
import javax.inject.Inject

/** Lifecycle host only. Requests/credentials stay in memory, never in Intent or notification. */
@AndroidEntryPoint
class ManagedTransferService : Service() {
    @Inject lateinit var manager: TransferManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var ownerId: String = ""
    private var latestStartId = 0
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Copies et déplacements", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ownerId = manager.state.value.id
        latestStartId = startId
        if (intent?.action == CANCEL) manager.cancel()
        startForeground(ID, notification())
        if (watcher == null) {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Bonobo:ManagedTransfer").apply {
                    acquire(6 * 60 * 60 * 1000L)
                }
            watcher = scope.launch {
                manager.state.collect { state ->
                    if (state.active) getSystemService(NotificationManager::class.java).notify(ID, notification())
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStartId) }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val state = manager.state.value
        val cancel = PendingIntent.getService(this, ID, Intent(this, ManagedTransferService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Bonobo Files — transfert")
            .setContentText(if (state.cancelRequested) "Annulation demandée…" else "${state.processedRoots}/${state.totalRoots} éléments traités")
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }
    override fun onDestroy() {
        if (manager.state.value.active && manager.state.value.id == ownerId) manager.cancel()
        watcher?.cancel(); scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "managed_transfers"; private const val ID = 1004; private const val CANCEL = "cancel" }
}
