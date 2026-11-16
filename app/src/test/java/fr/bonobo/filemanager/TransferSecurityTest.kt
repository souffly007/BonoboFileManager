package fr.bonobo.filemanager

import fr.bonobo.filemanager.service.TransferServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64

class TransferSecurityTest {
    private fun session(test: (TransferServer, Int) -> Unit) {
        val file = Files.createTempFile("transfer", ".txt").toFile().apply { writeText("secret-content") }
        val port = ServerSocket(0).use { it.localPort }
        val server = TransferServer("password123", port)
        try {
            assertTrue(server.start(file).isSuccess)
            test(server, port)
        } finally { server.stop(); file.delete() }
    }
    private fun request(port: Int, auth: String? = null, path: String = "/"): String = Socket("127.0.0.1", port).use {
        it.soTimeout = 3000
        val authorization = auth?.let { value -> "Authorization: Basic ${Base64.getEncoder().encodeToString(value.toByteArray())}\r\n" } ?: ""
        it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n${authorization}\r\n".toByteArray())
        it.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
    @Test fun authenticationRequiredAndCorrectPasswordWorks() = session { _, port ->
        assertTrue(request(port).startsWith("HTTP/1.1 401"))
        assertFalse(request(port, "bonobo:wrong").contains("secret-content"))
        val response = request(port, "bonobo:password123")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("\r\n\r\nsecret-content"))
        assertTrue(request(port, "bonobo:password123", "/other").startsWith("HTTP/1.1 404"))
    }
    @Test fun stopClosesPendingClient() = session { server, port ->
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())
            server.stop()
            try { assertEquals(-1, socket.getInputStream().read()) }
            catch (_: java.net.SocketException) { /* Reset also terminates the client. */ }
        }
    }
    @Test fun occupiedPortIsReported() {
        ServerSocket(0).use { busy ->
            val server = TransferServer("password123", busy.localPort)
            val file = Files.createTempFile("transfer", ".txt").toFile()
            try { assertTrue(server.start(file).isFailure) }
            finally { server.stop(); file.delete() }
        }
    }
    @Test fun shortPasswordRejected() {
        assertTrue(runCatching { TransferServer("short") }.isFailure)
    }
}
