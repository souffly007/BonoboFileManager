package fr.bonobo.filemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui héberge le serveur SFTP (SftpServerManager).
 *
 * Sans ce Service (et sa notification), Android — en particulier MIUI/HyperOS
 * sur les appareils Xiaomi/Poco — suspend ou tue le processus de l'app dès
 * qu'elle passe en arrière-plan ou que l'écran s'éteint, ce qui coupait le
 * partage SFTP en cours de route. FtpService et HttpsShareService suivent
 * déjà ce schéma ; SftpServerManager était le seul à en être dépourvu.
 */
class SftpService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CHANNEL_ID = "sftp_server_channel"
        private const val NOTIFICATION_ID = 1002

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        private val _isStarting = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = _isStarting
        private val _info = MutableStateFlow<SftpServerManager.Info?>(null)
        val info: StateFlow<SftpServerManager.Info?> = _info
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error

        fun start(context: Context) {
            val intent = Intent(context, SftpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SftpService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (_isRunning.value || _isStarting.value) return START_STICKY
        _error.value = null
        _isStarting.value = true
        startForeground(NOTIFICATION_ID, createNotification("Démarrage du partage SFTP…"))

        serviceScope.launch {
            SftpServerManager.start(applicationContext).fold(
                onSuccess = { result ->
                    _info.value = result
                    _isRunning.value = true
                    postNotification(
                        NOTIFICATION_ID,
                        createNotification("Partage SFTP actif — port ${result.port}")
                    )
                },
                onFailure = { t ->
                    val root = generateSequence(t) { it.cause }.last()
                    _error.value = "Démarrage SFTP impossible : ${root.javaClass.simpleName}: ${root.message ?: "cause inconnue"}"
                    _isRunning.value = false
                    _info.value = null
                    stopSelf()
                }
            )
            _isStarting.value = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { SftpServerManager.stop() }
        _isRunning.value = false
        _isStarting.value = false
        _info.value = null
        super.onDestroy()
    }

    private fun postNotification(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Partage SFTP",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bonobo FileManager")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
