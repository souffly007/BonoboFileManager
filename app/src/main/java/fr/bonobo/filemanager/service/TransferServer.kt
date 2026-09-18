package fr.bonobo.filemanager.service

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** One sharing session. Authentication is mandatory; HTTP itself is not encrypted. */
class TransferServer(password: String, private val port: Int = 8080) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val expected = digest("bonobo:$password".toByteArray(Charsets.UTF_8))
    private var failures = 0
    private var failureWindow = System.nanoTime()

    init { require(password.length >= 8) { "Mot de passe de 8 caractères minimum" } }

    @Synchronized
    fun start(file: File): Result<Unit> = runCatching {
        require(file.isFile && file.canRead()) { "Fichier source inaccessible" }
        check(scope.isActive) { "Créez une nouvelle session de transfert" }
        if (running.get()) return@runCatching
        val listener = ServerSocket(port)
        serverSocket = listener
        running.set(true)
        scope.launch {
            try {
                while (running.get()) {
                    val client = listener.accept()
                    synchronized(clients) {
                        if (!running.get() || clients.size >= 4) client.close()
                        else {
                            clients.add(client)
                            launch { handleClient(client, file) }
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing the listener is the normal shutdown path.
            } finally { stop() }
        }
    }

    private suspend fun handleClient(socket: Socket, file: File) = coroutineScope {
        val deadline = launch { delay(10 * 60_000L); socket.close() }
        try {
            val headers = readRequestHeaders(socket)
            val request = headers.firstOrNull()?.split(' ') ?: emptyList()
            val output = socket.getOutputStream()
            if (request.size != 3 || request[0] != "GET" || request[1] != "/" ||
                request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
                respond(output, "404 Not Found")
                return@coroutineScope
            }
            val authorization = headers.drop(1).filter { it.startsWith("Authorization:", true) }
            if (isThrottled()) {
                respond(output, "429 Too Many Requests", "Retry-After: 60\r\n")
                return@coroutineScope
            }
            if (authorization.size != 1 || !isAuthorized(authorization.single().substringAfter(':').trim())) {
                recordFailure()
                respond(output, "401 Unauthorized",
                    "WWW-Authenticate: Basic realm=\"Bonobo\", charset=\"UTF-8\"\r\n")
                return@coroutineScope
            }
            file.inputStream().use { input ->
                val size = input.channel.size()
                val safeName = file.name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
                val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: $size\r\nContent-Disposition: attachment; filename=\"$safeName\"\r\n" +
                    "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0 && running.get()) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("Source modifiée pendant le transfert")
                    output.write(buffer, 0, n)
                    remaining -= n
                }
                output.flush()
            }
        } catch (_: IOException) {
            // Invalid/incomplete request, disconnect or session timeout: send no file.
        } finally {
            deadline.cancel()
            clients.remove(socket)
            socket.close()
        }
    }

    private fun respond(output: OutputStream, status: String, extra: String = "") {
        output.write(("HTTP/1.1 $status\r\n${extra}Cache-Control: no-store\r\n" +
            "Connection: close\r\nContent-Length: 0\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun readRequestHeaders(socket: Socket): List<String> {
        val input = socket.getInputStream()
        val end = System.nanoTime() + 5_000_000_000L
        val lines = mutableListOf<String>()
        var total = 0
        while (lines.size < 64) {
            val line = StringBuilder()
            while (true) {
                val remainingMs = (end - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw IOException("En-têtes trop lents")
                socket.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                val value = input.read()
                if (value < 0) throw EOFException()
                if (++total > 16_384 || line.length > 2048) throw IOException("En-têtes trop longs")
                if (value == '\n'.code) break
                if (value != '\r'.code) line.append(value.toChar())
            }
            if (line.isEmpty()) return lines
            lines += line.toString()
        }
        throw IOException("Trop d'en-têtes")
    }

    private fun isAuthorized(header: String): Boolean {
        if (!header.startsWith("Basic ", true)) return false
        return try {
            MessageDigest.isEqual(expected, digest(Base64.getDecoder().decode(header.substringAfter(' ').trim())))
        } catch (_: IllegalArgumentException) { false }
    }

    @Synchronized private fun isThrottled(): Boolean {
        if (System.nanoTime() - failureWindow > 60_000_000_000L) {
            failures = 0
            failureWindow = System.nanoTime()
        }
        return failures >= 20
    }
    @Synchronized private fun recordFailure() { failures++ }

    @Synchronized
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        synchronized(clients) {
            clients.toList().forEach { try { it.close() } catch (_: IOException) { } }
            clients.clear()
        }
        expected.fill(0)
        scope.cancel()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
