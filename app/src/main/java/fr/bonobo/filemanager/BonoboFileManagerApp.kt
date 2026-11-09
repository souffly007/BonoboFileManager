package fr.bonobo.filemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

@HiltAndroidApp
class BonoboFileManagerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        fr.bonobo.filemanager.diagnostic.Diagnostic.journal = fr.bonobo.filemanager.diagnostic.TechnicalJournal(
            java.io.File(noBackupFilesDir, "technical-diagnostic"))
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                private var seen = false
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    val event = when (intent?.action) {
                        android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                            if (seen) fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_RECONNECTED
                            else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.USB_DETECTED
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
        installFullBouncyCastle()
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
