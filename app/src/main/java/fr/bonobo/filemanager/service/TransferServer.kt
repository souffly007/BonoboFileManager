package fr.bonobo.filemanager.service

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TransferServer(private val port: Int = 8080) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(file: File) {
        if (isRunning.get()) return
        isRunning.set(true)
        
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client, file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stop()
            }
        }
    }

    private suspend fun handleClient(socket: Socket, file: File) = withContext(Dispatchers.IO) {
        try {
            val output = socket.getOutputStream()
            val writer = PrintWriter(output)
            
            // Simple HTTP Response
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/octet-stream")
            writer.println("Content-Length: ${file.length()}")
            writer.println("Content-Disposition: attachment; filename=\"${file.name}\"")
            writer.println("Connection: close")
            writer.println()
            writer.flush()
            
            file.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }
}
