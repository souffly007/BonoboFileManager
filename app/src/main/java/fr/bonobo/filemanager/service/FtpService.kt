package fr.bonobo.filemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.ftpserver.FtpServer
import java.io.File

class FtpService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    @Volatile private var destroyed = false
    private var server: FtpServer? = null
    private var sessionUsers: SessionUserManager? = null

    companion object {
        private const val CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PASSWORD = "ftp_password"
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        private val _password = MutableStateFlow<String?>(null)
        val password: StateFlow<String?> = _password
        private val _fingerprint = MutableStateFlow<String?>(null)
        val fingerprint: StateFlow<String?> = _fingerprint
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error
        private val _isStarting = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = _isStarting

        fun start(context: Context, password: String) {
            require(password.length >= 8) { "Le mot de passe FTP doit contenir au moins 8 caractères" }
            val intent = Intent(context, FtpService::class.java)
                .putExtra(EXTRA_PASSWORD, password)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val password = intent?.getStringExtra(EXTRA_PASSWORD)
        intent?.removeExtra(EXTRA_PASSWORD)
        if (_isRunning.value || _isStarting.value) return START_NOT_STICKY
        if (password == null || password.length < 8) {
            stopSelf()
            return START_NOT_STICKY
        }
        _error.value = null
        _isStarting.value = true
        startForeground(NOTIFICATION_ID, createNotification("Démarrage FTPS sécurisé…"))
        serviceScope.launch { startFtpServer(password) }
        return START_NOT_STICKY
    }

    private fun startFtpServer(password: String) = synchronized(lifecycleLock) {
        var candidate: FtpServer? = null
        try {
            if (destroyed) return@synchronized
            val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bonobo-Partage")
            check(shared.isDirectory || shared.mkdirs()) { "Impossible de créer le dossier partagé" }
            val identity = FtpsIdentity.load()
            if (destroyed) return@synchronized
            val users = SessionUserManager(FtpsConfiguration.user(shared), password)
            sessionUsers = users
            candidate = FtpsConfiguration.factory(users, identity.tls).createServer()
            candidate.start()
            if (destroyed) {
                candidate.stop()
                users.clear()
                return@synchronized
            }
            server = candidate
            _fingerprint.value = identity.fingerprint
            _password.value = password
            _isRunning.value = true
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, createNotification("Serveur FTPS actif — TLS obligatoire"))
        } catch (e: Exception) {
            runCatching { candidate?.stop() }
            sessionUsers?.clear()
            sessionUsers = null
            _password.value = null
            _fingerprint.value = null
            _isRunning.value = false
            _error.value = "Démarrage FTPS impossible : ${e.localizedMessage ?: e.javaClass.simpleName}"
            stopSelf()
        } finally {
            _isStarting.value = false
        }
    }

    private fun stopFtpServer() = synchronized(lifecycleLock) {
        try {
            server?.stop()
        } finally {
            server = null
            sessionUsers?.clear()
            sessionUsers = null
            _password.value = null
            _isRunning.value = false
            _fingerprint.value = null
            _isStarting.value = false
        }
    }

    override fun onDestroy() {
        destroyed = true
        serviceScope.cancel()
        stopFtpServer()
        _password.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serveur FTPS sécurisé",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
