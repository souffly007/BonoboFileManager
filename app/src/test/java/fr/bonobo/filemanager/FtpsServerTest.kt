package fr.bonobo.filemanager

import fr.bonobo.filemanager.service.*
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginRequest
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class FtpsServerTest {
    private lateinit var server: FtpServer
    private lateinit var users: SessionUserManager
    private lateinit var directory: File
    private var port = 0
    private val clients = mutableListOf<FTPClient>()

    companion object {
        private const val PASSWORD = "test-secret-123"
        private lateinit var tls: SSLContext
        private lateinit var trustedClientTls: SSLContext
        @JvmStatic @BeforeClass fun identity() {
            val dir = Files.createTempDirectory("bonobo-test-cert").toFile()
            try {
                val file = File(dir, "test.p12")
                val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").absolutePath,
                    "-genkeypair", "-alias", "test", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                    "-validity", "2", "-storetype", "PKCS12", "-keystore", file.absolutePath,
                    "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt")
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
                val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
                val keys = CertificateKeyManager("test", store.getKey("test", PASSWORD.toCharArray()) as java.security.PrivateKey,
                    store.getCertificateChain("test").map { it as java.security.cert.X509Certificate }.toTypedArray())
                tls = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), null, null) }
                val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null); setCertificateEntry("test", store.getCertificate("test"))
                }
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                trustedClientTls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            } finally { dir.deleteRecursively() }
        }
    }

    @Before fun start() {
        directory = Files.createTempDirectory("bonobo-ftps").toFile()
        users = SessionUserManager(FtpsConfiguration.user(directory), PASSWORD)
        port = ServerSocket(0).use { it.localPort }
        server = FtpsConfiguration.factory(users, ContextSslConfiguration(tls), port).createServer()
        server.start()
    }
    @After fun stop() {
        clients.forEach { runCatching { it.disconnect() } }
        server.stop(); users.clear(); directory.deleteRecursively()
    }
    private fun secure(): FTPSClient = FTPSClient(false, trustedClientTls).apply {
        clients.add(this)
        defaultTimeout = 4000; connectTimeout = 4000
        setDataTimeout(java.time.Duration.ofSeconds(4))
        isEndpointCheckingEnabled = true
        setEnabledProtocols(arrayOf("TLSv1.2"))
        connect("127.0.0.1", port); soTimeout = 4000
    }

    @Test fun missingPermissionExplains421AndConfiguredUserCanLogin() {
        val request = ConcurrentLoginRequest(1, 1)
        assertNull(BaseUser().authorize(request))
        assertNotNull(FtpsConfiguration.user(directory).authorize(request))
        val a = secure(); val b = secure()
        assertTrue(a.replyString, a.login("bonobo", PASSWORD))
        assertTrue(b.replyString, b.login("bonobo", PASSWORD))
    }
    @Test fun clearLoginIsRejected() {
        val client = FTPClient().apply { clients.add(this); connect("127.0.0.1", port); soTimeout = 4000 }
        assertEquals(534, client.sendCommand("USER", "bonobo"))
        assertEquals(534, client.sendCommand("PASS", PASSWORD))
        assertEquals(504, client.sendCommand("AUTH", "SSL"))
    }
    @Test fun badPasswordIsRejectedOverTls() {
        val client = secure()
        assertFalse(client.login("bonobo", "incorrect-password"))
        assertEquals(530, client.replyCode)
    }
    @Test fun encryptedUploadListingAndDownload() {
        val client = secure()
        assertTrue(client.login("bonobo", PASSWORD))
        client.execPBSZ(0); client.execPROT("P"); client.enterLocalPassiveMode()
        client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
        val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        assertTrue(client.replyString, client.storeFile("essai.bin", ByteArrayInputStream(bytes)))
        assertTrue(client.listNames().contains("essai.bin"))
        val output = ByteArrayOutputStream()
        assertTrue(client.replyString, client.retrieveFile("essai.bin", output))
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(client.logout())
    }
    @Test fun downgradeIsRejectedAndReconnectWorks() {
        val client = secure(); assertTrue(client.login("bonobo", PASSWORD))
        assertEquals(534, client.sendCommand("PROT", "C"))
        assertThrows(javax.net.ssl.SSLException::class.java) { client.sendCommand("CCC") }
        assertEquals(534, client.replyCode)
        client.execPBSZ(0); client.execPROT("P")
        assertTrue(client.logout()); client.disconnect()
        val next = secure(); assertTrue(next.replyString, next.login("bonobo", PASSWORD))
    }
    @Test fun seedboxDownloadPathUsesBinaryTlsAndChecksCompletion() {
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        File(directory, "film.bin").writeBytes(bytes)
        val client = secure(); assertTrue(client.login("bonobo", PASSWORD))
        client.execPBSZ(0); client.execPROT("P"); client.enterLocalPassiveMode()
        val output = ByteArrayOutputStream()
        var count = 0L
        fr.bonobo.filemanager.data.repository.FtpClientRepository.transfer(client, "/film.bin", output,
            bytes.size.toLong(), progress = { count = it })
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(bytes.size.toLong(), count)
        assertEquals(226, client.replyCode)
    }
    @Test fun missingRemoteFileIsNotAnEmptySuccessfulDownload() {
        val client = secure(); assertTrue(client.login("bonobo", PASSWORD))
        client.execPBSZ(0); client.execPROT("P"); client.enterLocalPassiveMode()
        assertThrows(IllegalStateException::class.java) {
            fr.bonobo.filemanager.data.repository.FtpClientRepository.transfer(client, "/absent.bin", ByteArrayOutputStream(), 10)
        }
    }

}
