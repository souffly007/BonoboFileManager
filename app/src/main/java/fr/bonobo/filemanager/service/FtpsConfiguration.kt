package fr.bonobo.filemanager.service

import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.*
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.ClientAuth
import org.apache.ftpserver.ssl.SslConfiguration
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.util.Locale
import javax.net.ssl.SSLContext

/** The same configuration is exercised by the JVM socket integration tests. */
object FtpsConfiguration {
    fun user(home: File) = BaseUser().apply {
        name = "bonobo"
        homeDirectory = home.absolutePath
        maxIdleTime = 120
        authorities = listOf(WritePermission(), ConcurrentLoginPermission(10, 5), TransferRatePermission(0, 0))
    }

    fun factory(users: SessionUserManager, tls: SslConfiguration, port: Int = 2121): FtpServerFactory {
        val data = DataConnectionConfigurationFactory().apply {
            isImplicitSsl = true // Even a client omitting PROT must never get a clear data channel.
            sslConfiguration = tls
            isActiveEnabled = false
            isPassiveIpCheck = true
        }
        val listener = ListenerFactory().apply {
            this.port = port
            isImplicitSsl = false // Explicit AUTH TLS, compatible with FileZilla on port 2121.
            sslConfiguration = tls
            dataConnectionConfiguration = data.createDataConnectionConfiguration()
        }
        return FtpServerFactory().apply {
            userManager = users
            ftplets = mutableMapOf("require-tls" to RequireTlsFtplet())
            addListener("default", listener.createListener())
        }
    }
}

class ContextSslConfiguration(private val context: SSLContext) : SslConfiguration {
    override fun getSSLContext() = context
    override fun getSSLContext(protocol: String) = context
    override fun getSocketFactory() = context.socketFactory
    override fun getEnabledCipherSuites(): Array<String>? = null
    // TLS 1.2 works on all supported Android versions (API 26+) and current FTP clients.
    override fun getEnabledProtocols() = arrayOf("TLSv1.2")
    override fun getClientAuth() = ClientAuth.NONE
}

class RequireTlsFtplet : DefaultFtplet() {
    override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult {
        val command = request.command.uppercase(Locale.ROOT)
        val reply = when {
            command == "CCC" -> DefaultFtpReply(534, "TLS must remain enabled.")
            command == "AUTH" && !request.argument.equals("TLS", ignoreCase = true) ->
                DefaultFtpReply(504, "Use AUTH TLS.")
            !session.isSecure && command !in setOf("AUTH", "FEAT", "SYST", "NOOP", "QUIT") ->
                DefaultFtpReply(534, "TLS required. Use AUTH TLS before login.")
            command == "PROT" && !request.argument.equals("P", ignoreCase = true) ->
                DefaultFtpReply(534, "Encrypted data required. Use PROT P.")
            else -> null
        }
        if (reply != null) {
            session.write(reply)
            return FtpletResult.SKIP
        }
        return FtpletResult.DEFAULT
    }
}
