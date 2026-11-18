package fr.bonobo.filemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

@HiltAndroidApp
class BonoboFileManagerApp : Application(), ImageLoaderFactory {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @javax.inject.Inject lateinit var smbClient: fr.bonobo.filemanager.data.repository.SmbClientRepository
    @javax.inject.Inject lateinit var remoteConnections: fr.bonobo.filemanager.domain.repository.IRemoteRepository

    override fun onCreate() {
        super.onCreate()
        fr.bonobo.filemanager.diagnostic.Diagnostic.journal = fr.bonobo.filemanager.diagnostic.TechnicalJournal(
            java.io.File(noBackupFilesDir, "technical-diagnostic"))
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                private var seen = false
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    val event = when (intent?.action) {
                        android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            // The volume is mounted a few seconds after the device appears.
                            schedulePendingCleanup(listOf(3_000L, 8_000L, 20_000L))
                            if (seen) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_RECONNECTED
                            else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_DETECTED
                        }
                        android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED ->
                            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_REMOVED
                        else -> return
                    }
                    seen = true
                    fr.bonobo.filemanager.diagnostic.Diagnostic.event(event)
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, receiver,
                android.content.IntentFilter().apply {
                    addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
                }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
        runCatching {
            // Volume mounted (USB key, SD card): delete the partial files recorded for it.
            val mounted = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        android.content.Intent.ACTION_MEDIA_MOUNTED -> {
                            fr.bonobo.filemanager.util.UsbVolumes.markMounted(intent.data?.path)
                            schedulePendingCleanup(listOf(500L))
                        }
                        else -> fr.bonobo.filemanager.util.UsbVolumes.notifyChanged()
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, mounted,
                android.content.IntentFilter(android.content.Intent.ACTION_MEDIA_MOUNTED).apply {
                    addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
                    addAction(android.content.Intent.ACTION_MEDIA_EJECT)
                    addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
                    addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
                    addDataScheme("file")
                },
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
        // The key may have been reconnected while the app was closed.
        schedulePendingCleanup(listOf(2_000L))
        scheduleSmbCleanup()
        installFullBouncyCastle()
    }

    /**
     * App killed during an SMB write: its receipts are adopted at start, then the partial files are
     * removed from each server that is reachable (otherwise at the next connection to it).
     */
    private fun scheduleSmbCleanup() {
        background.launch {
            delay(5_000L)
            runCatching {
                val ids = smbClient.pendingConnectionIds()
                if (ids.isEmpty()) return@runCatching
                val connections = remoteConnections.getAllConnections().first()
                connections.filter { it.id in ids && it.type == fr.bonobo.filemanager.domain.model.ConnectionType.SMB }.forEach { connection ->
                    runCatching { smbClient.cleanPending(connection) }
                    smbClient.release(connection)
                }
            }
        }
    }

    private fun schedulePendingCleanup(delays: List<Long>) {
        background.launch {
            for (wait in delays) {
                delay(wait)
                val summary = runCatching { fr.bonobo.filemanager.util.PendingCleanup.runDeferred(this@BonoboFileManagerApp) }.getOrNull()
                if (summary != null && summary.deleted + summary.leftover > 0 && summary.unknown == 0) break
            }
        }
    }

    /**
     * Android embarque son propre provider "BC" allégé (basé sur une vieille
     * version de BouncyCastle), qui ne fournit pas tous les jeux de
     * paramètres de courbes EC (ex. nistp384/secp384r1). Comme
     * Security.addProvider() n'écrase jamais un provider existant du même
     * nom, il faut d'abord retirer le "BC" d'Android puis insérer le vrai
     * bcprov-jdk18on à sa place, avant toute utilisation de SSHD/TLS.
     */
    private fun installFullBouncyCastle() {
        runCatching {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
