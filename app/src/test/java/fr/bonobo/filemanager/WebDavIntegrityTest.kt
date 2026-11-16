package fr.bonobo.filemanager

import java.net.ServerSocket
import fr.bonobo.filemanager.data.repository.WebDavClientRepository
import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.RemoteConnection
import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WebDavIntegrityTest {
    private suspend fun digest(data: String) = IntegrityVerifier.fingerprint(object : IntegrityVerifier.Access {
        override fun children(path: String): List<IntegrityVerifier.Child>? = null
        override fun open(path: String) = ByteArrayInputStream(data.toByteArray())
    }, "test")
    private suspend fun fixture(body: String, tag: String?, deleteStatus: Int = 204,
        test: suspend (RemoteConnection, WebDavClientRepository, () -> Int, () -> String?) -> Unit) {
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val condition = java.util.concurrent.atomic.AtomicReference<String?>()
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val method = reader.readLine().substringBefore(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val bytes = if (method == "DELETE") {
                        deletes.incrementAndGet(); condition.set(headers["if-match"])
                        byteArrayOf()
                    } else body.toByteArray()
                    val status = if (method == "DELETE") deleteStatus else 200
                    val response = buildString {
                        append("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                        if (method != "DELETE" && tag != null) append("ETag: $tag\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
        }.apply { isDaemon = true; start() }
        try {
            val connection = RemoteConnection(name = "synthetic", host = "http://127.0.0.1:${server.localPort}",
                port = server.localPort, user = "", pass = "", type = ConnectionType.WEBDAV)
            test(connection, WebDavClientRepository(), { deletes.get() }, { condition.get() })
        } finally { server.close(); worker.join(2000) }
    }
    @Test fun verifiedGetUsesStrongEtagForConditionalDelete() = runBlocking {
        fixture("original", "\"version1\"") { connection, repository, deletes, condition ->
            repository.deleteVerifiedFile(connection, "/file", digest("original"))
            assertEquals(1, deletes()); assertEquals("\"version1\"", condition())
        }
    }
    @Test fun successfulHttpReplyWithCorruptContentNeverDeletes() = runBlocking {
        fixture("truncated", "\"v1\"") { connection, repository, deletes, _ ->
            assertTrue(runCatching { repository.deleteVerifiedFile(connection, "/file", digest("complete source")) }.isFailure)
            assertEquals(0, deletes())
        }
    }
    @Test fun absentOrWeakEtagPreservesRemoteSource() = runBlocking {
        for (tag in listOf(null, "W/\"weak\"")) fixture("original", tag) { connection, repository, deletes, _ ->
            assertTrue(runCatching { repository.deleteVerifiedFile(connection, "/file", digest("original")) }.isFailure)
            assertEquals(0, deletes())
        }
    }
    @Test fun versionChangedBeforeDeleteIsNotReportedAsSuccess() = runBlocking {
        fixture("original", "\"version1\"", 412) { connection, repository, deletes, condition ->
            assertTrue(runCatching { repository.deleteVerifiedFile(connection, "/file", digest("original")) }.isFailure)
            assertEquals(1, deletes()); assertEquals("\"version1\"", condition())
        }
    }
}
