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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpService : Service() {

    private var server: FtpServer? = null

    companion object {
        private const val CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 1001
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        fun start(context: Context) {
            val intent = Intent(context, FtpService::class.java)
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
        startForeground(NOTIFICATION_ID, createNotification("Serveur FTP actif"))
        startFtpServer()
        return START_STICKY
    }

    private fun startFtpServer() {
        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = 2121

            serverFactory.addListener("default", listenerFactory.createListener())

            val userManagerFactory = PropertiesUserManagerFactory()
            val userManager = userManagerFactory.createUserManager()

            val user = BaseUser()
            user.name = "bonobo"
            user.password = "bonobo"
            user.homeDirectory = Environment.getExternalStorageDirectory().absolutePath
            
            val authorities = mutableListOf<Authority>()
            authorities.add(WritePermission())
            user.authorities = authorities

            userManager.save(user)
            serverFactory.userManager = userManager

            val ftpServer = serverFactory.createServer()
            ftpServer.start()
            server = ftpServer
            _isRunning.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopFtpServer() {
        server?.stop()
        server = null
        _isRunning.value = false
    }

    override fun onDestroy() {
        stopFtpServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serveur FTP",
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
