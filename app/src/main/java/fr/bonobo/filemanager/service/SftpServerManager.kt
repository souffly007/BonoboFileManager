package fr.bonobo.filemanager.service

import android.content.Context
import android.os.Environment
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.nio.file.Paths
import java.security.SecureRandom

/** Petit serveur SFTP local pour partager Téléchargements/Bonobo-Partage sur le LAN. */
object SftpServerManager {
    data class Info(val port: Int, val username: String, val password: String, val root: File)

    private var server: SshServer? = null
    private var currentInfo: Info? = null

    @Synchronized
    fun start(context: Context, port: Int = 2222): Result<Info> = runCatching {
        currentInfo?.let { return@runCatching it }

        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Bonobo-Partage"
        ).apply { mkdirs() }

        val username = "bonobo"
        val password = randomPassword()

        // Apache MINA SSHD expects a user.home value on a regular JVM.
        // Android does not guarantee it, so give it an app-private home.
        if (System.getProperty("user.home").isNullOrBlank()) {
            System.setProperty("user.home", context.filesDir.absolutePath)
        }

        val sshd = SshServer.setUpDefaultServer().apply {
            this.port = port
            host = "0.0.0.0"
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                Paths.get(context.filesDir.absolutePath, "bonobo-sftp-hostkey.ser")
            )
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ ->
                user == username && pass == password
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        }
        sshd.start()
        server = sshd
        Info(port, username, password, root).also { currentInfo = it }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(true) }
        server = null
        currentInfo = null
    }

    fun info(): Info? = currentInfo
    fun isRunning(): Boolean = server?.isStarted == true

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString { repeat(14) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
