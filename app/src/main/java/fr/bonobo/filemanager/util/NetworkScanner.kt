package fr.bonobo.filemanager.util

import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Découverte LAN volontairement tolérante : certains PC Windows bloquent l'ICMP
 * mais laissent SMB/NetBIOS/HTTP accessibles. On teste donc plusieurs signaux.
 */
object NetworkScanner {

    suspend fun scanSubnet(baseIp: String): List<String> = withContext(Dispatchers.IO) {
        val prefix = baseIp.substringBeforeLast(".") + "."
        coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val host = prefix + i
                    if (isHostReachable(host)) host else null
                }
            }.awaitAll().filterNotNull().sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
        }
    }

    private fun isHostReachable(host: String): Boolean {
        // 1) ICMP/TCP reachability quand Android le permet.
        try {
            if (InetAddress.getByName(host).isReachable(350)) return true
        } catch (_: Exception) { }

        // 2) Services typiques d'un PC/NAS/serveur.
        val ports = intArrayOf(445, 139, 21, 80, 443)
        return ports.any { port ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 450)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
