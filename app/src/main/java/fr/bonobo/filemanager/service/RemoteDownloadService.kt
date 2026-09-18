package fr.bonobo.filemanager.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import fr.bonobo.filemanager.data.repository.RemoteFileRepository
import fr.bonobo.filemanager.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class RemoteDownloadState(val active: Boolean = false, val name: String = "", val bytes: Long = 0,
    val total: Long = 0, val message: String? = null, val failed: Boolean = false)

@AndroidEntryPoint
class RemoteDownloadService : Service() {
    @Inject lateinit var repository: RemoteFileRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null

    companion object {
        private const val CHANNEL = "remote_downloads"
        private const val ID = 1003
        private val busy = AtomicBoolean(false)
        private val mutableState = MutableStateFlow(RemoteDownloadState())
        val state = mutableState.asStateFlow()
        fun start(context: Context, connection: RemoteConnection, item: FileItem, destination: Uri?) {
            check(busy.compareAndSet(false, true)) { "Un téléchargement est déjà en cours" }
            val intent = Intent(context, RemoteDownloadService::class.java).apply {
                putExtra("host", connection.host); putExtra("port", connection.port)
                putExtra("user", connection.user); putExtra("password", connection.pass)
                putExtra("type", connection.type.name); putExtra("share", connection.share)
                putExtra("path", item.path); putExtra("name", item.name); putExtra("size", item.size)
                putExtra("mime", item.mimeType); putExtra("destination", destination?.toString())
            }
            mutableState.value = RemoteDownloadState(active = true, name = item.name, total = item.size)
            try { context.startForegroundService(intent) } catch (e: Exception) {
                busy.set(false)
                mutableState.value = RemoteDownloadState()
                throw e
            }
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, RemoteDownloadService::class.java).putExtra("cancel", true))
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements distants", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("cancel", false) == true) {
            cancelled.set(true)
            if (mutableState.value.active) mutableState.value = mutableState.value.copy(message = "Annulation…")
            else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val connection = RemoteConnection(name = "", host = intent.getStringExtra("host") ?: "",
            port = intent.getIntExtra("port", 21), user = intent.getStringExtra("user") ?: "",
            pass = intent.getStringExtra("password") ?: "",
            type = ConnectionType.valueOf(intent.getStringExtra("type") ?: "FTPS").let { if (it == ConnectionType.FTP) ConnectionType.FTPS else it }, share = intent.getStringExtra("share"))
        val name = intent.getStringExtra("name") ?: "fichier"
        val path = intent.getStringExtra("path") ?: ""
        val size = intent.getLongExtra("size", -1)
        val mime = intent.getStringExtra("mime") ?: "application/octet-stream"
        val destination = intent.getStringExtra("destination")?.let(Uri::parse)
        intent.replaceExtras(android.os.Bundle())
        cancelled.set(false)
        mutableState.value = RemoteDownloadState(active = true, name = name, total = size)
        startForeground(ID, notification())
        job = scope.launch {
            var target: RemoteDownloadTarget? = null
            val wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Bonobo:RemoteDownload")
            try {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                target = RemoteDownloadTarget.create(this@RemoteDownloadService, name, mime, destination, size)
                var lastUpdate = 0L
                target.output.use { output ->
                    repository.download(connection, path, output, size,
                        checkCancelled = {
                            ensureActive()
                            if (cancelled.get()) throw CancellationException("Téléchargement annulé")
                        }, progress = { bytes ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 300 || bytes == size) {
                                lastUpdate = now
                                mutableState.value = mutableState.value.copy(bytes = bytes)
                                getSystemService(NotificationManager::class.java).notify(ID, notification())
                            }
                        })
                }
                if (cancelled.get()) throw CancellationException("Téléchargement annulé")
                ensureActive()
                val saved = target.commit()
                mutableState.value = mutableState.value.copy(active = false, message = "Enregistré : $saved")
            } catch (e: Exception) {
                val cleaned = target?.abort() ?: destination?.let { RemoteDownloadTarget.deleteDocument(this@RemoteDownloadService, it) } ?: true
                val wasCancelled = e is CancellationException || cancelled.get()
                mutableState.value = mutableState.value.copy(active = false, failed = !wasCancelled || !cleaned,
                    message = (if (wasCancelled) "Téléchargement annulé" else "Téléchargement échoué : ${e.message}") +
                        if (!cleaned) ". Un fichier partiel reste à supprimer dans la destination." else "")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).notify(ID, notification())
                stopSelf(startId)
            }
        }
        job?.invokeOnCompletion { busy.set(false) }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val s = mutableState.value
        val percent = if (s.total > 0) (s.bytes.toDouble() / s.total * 100).toInt().coerceIn(0, 100) else 0
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(s.name).setContentText(s.message ?: "Téléchargement : $percent %")
            .setOnlyAlertOnce(true).setOngoing(s.active)
        if (s.active) {
            builder.setProgress(100, percent, s.total <= 0)
            val cancel = PendingIntent.getService(this, ID, Intent(this, RemoteDownloadService::class.java).putExtra("cancel", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Annuler", cancel)
        }
        return builder.build()
    }
    override fun onDestroy() { cancelled.set(true); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
}
