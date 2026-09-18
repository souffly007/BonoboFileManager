package fr.bonobo.filemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fr.bonobo.filemanager.util.SafeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.net.ssl.SSLServerSocket

data class HttpsShareState(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val fingerprint: String? = null,
    val error: String? = null
)

class HttpsShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: SSLServerSocket? = null
    private var shared: File? = null

    companion object {
        private const val CHANNEL = "https_share"
        private const val ID = 1004
        private const val EXTRA_START = "start"
        private val mutable = MutableStateFlow(HttpsShareState())
        val state = mutable.asStateFlow()

        fun start(context: Context) = context.startService(Intent(context, HttpsShareService::class.java).putExtra(EXTRA_START, true))
        fun stop(context: Context) = context.stopService(Intent(context, HttpsShareService::class.java))
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Partage HTTPS", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mutable.value.running || intent?.getBooleanExtra(EXTRA_START, false) != true) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification("Démarrage du partage HTTPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification("Démarrage du partage HTTPS…"))
        }
        scope.launch { startServer() }
        return START_NOT_STICKY
    }

    private fun startServer() {
        try {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bonobo-Partage")
            check(folder.isDirectory || folder.mkdirs()) { "Dossier partagé inaccessible" }
            val identity = FtpsIdentity.load()
            val socket = identity.tls.getSSLContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket
            socket.enabledProtocols = arrayOf("TLSv1.2")
            server = socket
            shared = folder
            val token = buildString {
                repeat(8) { append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[SecureRandom().nextInt(32)]) }
            }
            mutable.value = HttpsShareState(true, socket.localPort, token, identity.fingerprint)
            getSystemService(NotificationManager::class.java).notify(ID, notification("Partage HTTPS actif"))
            while (!socket.isClosed) runCatching { socket.accept() }.onSuccess { client ->
                scope.launch { handle(client as javax.net.ssl.SSLSocket, token) }
            }
        } catch (e: Exception) {
            mutable.value = HttpsShareState(error = "Démarrage HTTPS impossible : ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            runCatching { server?.close() }
            server = null
            shared = null
            mutable.value = HttpsShareState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handle(socket: javax.net.ssl.SSLSocket, token: String) {
        try {
            socket.use { client ->
            client.soTimeout = 20_000
            client.startHandshake()
            val input = BufferedInputStream(client.inputStream)
            val headerBytes = readHeaders(input) ?: return
            val header = String(headerBytes, StandardCharsets.UTF_8)
            val first = header.lineSequence().firstOrNull()?.split(' ') ?: return
            if (first.size < 2) return
            val method = first[0].uppercase()
            val target = first[1]
            val query = target.substringAfter('?', "").split('&').mapNotNull {
                val p = it.split('=', limit = 2); if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
            }.toMap()
            if (query["token"] != token) { respond(client, 403, "Accès refusé", "text/plain"); return }
            val name = query["name"]?.let(::safeName)
            val bodyLength = header.lineSequence().firstNotNullOfOrNull { line ->
                if (line.startsWith("Content-Length:", true)) line.substringAfter(':').trim().toLongOrNull() else null
            } ?: 0L
            val folder = shared ?: return
            when {
                method == "GET" && target.substringBefore('?') == "/" -> respond(client, 200, page(folder, token), "text/html; charset=utf-8")
                method == "GET" && target.substringBefore('?') == "/download" && name != null -> {
                    val file = SafeFiles.child(folder, name); if (file.isFile) sendFile(client, file, false, null) else respond(client, 404, "Introuvable", "text/plain")
                }
                method == "GET" && target.substringBefore('?') == "/stream" && name != null -> {
                    val file = SafeFiles.child(folder, name); if (file.isFile) sendFile(client, file, true, headerValue(header, "Range")) else respond(client, 404, "Introuvable", "text/plain")
                }
                method == "GET" && target.substringBefore('?') == "/properties" && name != null -> {
                    val file = SafeFiles.child(folder, name); if (file.isFile) respond(client, 200, properties(file), "text/html; charset=utf-8") else respond(client, 404, "Introuvable", "text/plain")
                }
                method == "PUT" && target.substringBefore('?') == "/upload" && name != null && bodyLength <= 512L * 1024 * 1024 -> {
                    val file = SafeFiles.child(folder, name); file.outputStream().use { output -> copyExactly(input, output, bodyLength) }
                    respond(client, 200, "Envoyé", "text/plain")
                }
                method == "POST" && target.substringBefore('?') == "/delete" && name != null -> {
                    val file = SafeFiles.child(folder, name); if (file.isFile) file.delete()
                    respond(client, 200, "Supprimé", "text/plain")
                }
                else -> respond(client, 404, "Requête inconnue", "text/plain")
            }
            }
        } catch (_: Exception) {
            // Un navigateur peut interrompre TLS après avoir refusé le certificat local.
            // Cette fermeture distante ne doit jamais faire tomber l'application.
        }
    }

    private fun page(folder: File, token: String): String {
        val rows = folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }.joinToString("") {
            val encoded = java.net.URLEncoder.encode(it.name, "UTF-8")
            val type = mimeType(it.name)
            val copy = if (type.startsWith("video/") || type.startsWith("audio/")) "<button onclick=\"copyVlc('$encoded')\">Copier le lien pour VLC</button>" else ""
            "<li><span class='name'>${escape(it.name)}</span> <small>${formatSize(it.length())}</small> <details><summary>⋮</summary><div class='menu'>$copy <a href='/properties?token=$token&name=$encoded' target='_blank'>Propriétés</a> <a href='/download?token=$token&name=$encoded'>Télécharger</a> <button onclick=\"del('$encoded')\">Supprimer</button></div></details></li>"
        }
        return """<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>Bonobo HTTPS</title>
        <style>body{font:16px system-ui;max-width:850px;margin:2em auto;padding:0 1em;background:#10151b;color:#eef2f7}a{color:#83c7ff;margin-right:.8em}li{display:flex;align-items:center;gap:.6em;margin:.8em 0;padding:.7em;background:#1b232c;border-radius:10px}.name{flex:1;overflow-wrap:anywhere}small{color:#aab7c5}details{position:relative}summary{cursor:pointer;font-size:1.4em;list-style:none;padding:.1em .4em}.menu{position:absolute;right:0;z-index:2;white-space:nowrap;background:#26313d;padding:.8em;border-radius:8px;box-shadow:0 4px 16px #0008}button{background:none;border:0;color:#ff9b9b;font:inherit;cursor:pointer}</style>
        <h1>Bonobo Files — partage sécurisé</h1><p>Les vidéos et médias compatibles peuvent être lus directement.</p><input id=f type=file><button onclick=up()>Envoyer</button><ul>$rows</ul>
        <script>const t='$token';function stream(n){return location.origin+'/stream?token='+t+'&name='+n}async function copyVlc(n){await navigator.clipboard.writeText(stream(n));alert('Lien copié. Dans VLC : Média → Ouvrir un flux réseau.')}async function up(){let f=document.getElementById('f').files[0];if(!f)return;await fetch('/upload?token='+t+'&name='+encodeURIComponent(f.name),{method:'PUT',body:f});location.reload()}async function del(n){await fetch('/delete?token='+t+'&name='+n,{method:'POST'});location.reload()}</script>"""
    }

    private fun sendFile(socket: javax.net.ssl.SSLSocket, file: File, inline: Boolean, range: String?) {
        val length = file.length(); var start = 0L; var end = length - 1; var status = "200 OK"
        if (range?.startsWith("bytes=") == true) { val parts = range.removePrefix("bytes=").substringBefore(',').split('-', limit = 2); start = parts[0].toLongOrNull() ?: 0L; end = parts.getOrNull(1)?.toLongOrNull() ?: end; if (start in 0 until length) { end = end.coerceIn(start, length - 1); status = "206 Partial Content" } else end = -1L }
        if (end < start) { socket.outputStream.use { it.write("HTTP/1.1 416 Range Not Satisfiable\r\nConnection: close\r\n\r\n".toByteArray()) }; return }
        val count = end - start + 1; val disposition = if (inline) "inline" else "attachment"; val out = socket.outputStream
        out.write("HTTP/1.1 $status\r\nContent-Type: ${mimeType(file.name)}\r\nContent-Length: $count\r\nContent-Range: bytes $start-$end/$length\r\nAccept-Ranges: bytes\r\nContent-Disposition: $disposition; filename=\"${escape(file.name)}\"\r\nConnection: close\r\n\r\n".toByteArray())
        RandomAccessFile(file, "r").use { input -> input.seek(start); val buffer = ByteArray(64 * 1024); var left = count; while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n <= 0) break; out.write(buffer, 0, n); left -= n } }; out.flush()
    }
    private fun headerValue(header: String, name: String) = header.lineSequence().firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()
    private fun mimeType(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "txt", "log", "md", "csv", "json", "xml", "kt", "java", "html", "css", "js" -> "text/plain"; "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; else -> "application/octet-stream" }
    private fun formatSize(bytes: Long): String { if (bytes < 1024) return "$bytes o"; val units = arrayOf("Ko", "Mo", "Go", "To"); var value = bytes.toDouble(); var index = -1; do { value /= 1024; index++ } while (value >= 1024 && index < units.lastIndex); return "%.1f %s".format(java.util.Locale.FRANCE, value, units[index]) }
    private fun properties(file: File) = "<html><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>Propriétés</title><body style='font:16px system-ui;max-width:700px;margin:2em auto'><h1>Propriétés</h1><p><b>Nom :</b> ${escape(file.name)}</p><p><b>Taille :</b> ${formatSize(file.length())}</p><p><b>Type :</b> ${mimeType(file.name)}</p><p><b>Modifié le :</b> ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))}</p></body></html>"
    private fun respond(socket: javax.net.ssl.SSLSocket, code: Int, body: String, type: String) {
        val bytes = body.toByteArray(); socket.outputStream.use { it.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); it.write(bytes); it.flush() }
    }
    private fun readHeaders(input: BufferedInputStream): ByteArray? { val b = java.io.ByteArrayOutputStream(); var last = 0; while (b.size() < 16384) { val c = input.read(); if (c < 0) return null; b.write(c); if (last == '\r'.code && c == '\n'.code) { val a = b.toByteArray(); if (a.size >= 4 && a[a.size-4] == '\r'.code.toByte()) return a }; last = c }; return null }
    private fun copyExactly(input: BufferedInputStream, output: java.io.OutputStream, count: Long) { var left = count; val buffer = ByteArray(64 * 1024); while (left > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); check(n > 0); output.write(buffer, 0, n); left -= n } }
    private fun safeName(value: String) = value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), "_").take(120).takeIf { it.isNotBlank() && it != "." && it != ".." }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Bonobo FileManager HTTPS").setContentText(text).setOngoing(true).build()
    override fun onDestroy() { runCatching { server?.close() }; scope.cancel(); mutable.value = HttpsShareState(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
