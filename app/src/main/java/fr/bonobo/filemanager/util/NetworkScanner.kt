package fr.bonobo.filemanager.util

import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object NetworkScanner {

    suspend fun scanSubnet(baseIp: String): List<String> = withContext(Dispatchers.IO) {
        val jobs = mutableListOf<Deferred<String?>>()
        val prefix = baseIp.substringBeforeLast(".") + "."
        
        for (i in 1..254) {
            val host = prefix + i
            jobs.add(async {
                if (isHostReachable(host)) host else null
            })
        }
        
        jobs.awaitAll().filterNotNull()
    }

    private fun isHostReachable(host: String): Boolean {
        return try {
            // Check common ports: 21 (FTP), 445 (SMB), 80 (HTTP)
            val ports = listOf(445, 21, 139)
            ports.any { port ->
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), 200)
                    socket.close()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
