package fr.bonobo.filemanager.data.repository

import android.webkit.MimeTypeMap
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.model.RemoteConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.OutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Client WebDAV volontairement simple : HTTPS/HTTP standard, Basic Auth, PROPFIND/GET/PUT/DELETE. */
@Singleton
class WebDavClientRepository @Inject constructor() {
    private val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    suspend fun listRemoteFiles(connection: RemoteConnection, path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = urlFor(connection, path, directory = true)
            val body = """<?xml version="1.0" encoding="utf-8" ?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/></d:prop></d:propfind>"""
            val request = requestBuilder(connection, url)
                .header("Depth", "1")
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("WebDAV ${response.code}: ${response.message}")
                parseMultiStatus(response.body?.string().orEmpty(), path).drop(1)
            }
        }
    }

    suspend fun deleteFile(connection: RemoteConnection, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(requestBuilder(connection, urlFor(connection, path)).delete().build()).execute().use { r ->
                if (!r.isSuccessful) error("WebDAV ${r.code}: ${r.message}")
            }
        }
    }

    suspend fun deleteVerifiedFile(connection: RemoteConnection, path: String, expected: String) = withContext(Dispatchers.IO) {
        val request = requestBuilder(connection, urlFor(connection, path)).header("Cache-Control", "no-cache").get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful && response.request.url == request.url) { "Relecture WebDAV refusée ou redirigée : source conservée" }
            val tag = response.header("ETag")
            require(tag != null && tag.startsWith('"') && tag.endsWith('"')) { "ETag WebDAV fort absent : suppression refusée, copie conservée" }
            val input = response.body?.byteStream() ?: error("Source WebDAV illisible")
            val access = object : IntegrityVerifier.Access {
                override fun children(path: String): List<IntegrityVerifier.Child>? = null
                override fun open(path: String) = input
            }
            check(IntegrityVerifier.fingerprint(access, path) == expected) { "Source WebDAV modifiée : suppression refusée" }
            currentCoroutineContext().ensureActive()
            client.newCall(requestBuilder(connection, urlFor(connection, path)).header("If-Match", tag).delete().build()).execute().use { deleted ->
                check(deleted.isSuccessful) { "Copie vérifiée ; suppression conditionnelle WebDAV non confirmée (${deleted.code})" }
            }
        }
    }

    suspend fun downloadToFile(connection: RemoteConnection, path: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output -> download(connection, path, output, {}, {}) }
            destination
        }
    }

    suspend fun uploadFromFile(connection: RemoteConnection, path: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
            val req = requestBuilder(connection, urlFor(connection, path)).put(source.asRequestBody(mime.toMediaTypeOrNull())).build()
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("WebDAV ${r.code}: ${r.message}") }
        }
    }

    suspend fun download(connection: RemoteConnection, path: String, output: OutputStream, checkCancelled: () -> Unit, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val req = requestBuilder(connection, urlFor(connection, path)).header("Cache-Control", "no-cache").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("WebDAV ${r.code}: ${r.message}")
            val input = r.body?.byteStream() ?: error("Réponse WebDAV vide")
            val operationContext = currentCoroutineContext()
            fr.bonobo.filemanager.util.DownloadStream.copy(input, output, r.body?.contentLength() ?: -1,
                { operationContext.ensureActive(); checkCancelled() }, progress)
        }
    }

    private fun requestBuilder(c: RemoteConnection, url: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", "Bonobo-Files/1.3")
        if (c.user.isNotBlank()) b.header("Authorization", Credentials.basic(c.user, c.pass))
        return b
    }

    private fun urlFor(c: RemoteConnection, path: String, directory: Boolean = false): String {
        var host = c.host.trim().trimEnd('/')
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "https://$host"
        val afterScheme = host.substringAfter("://")
        if (!afterScheme.substringBefore('/').contains(':') && c.port > 0 && c.port !in listOf(80, 443)) {
            val scheme = host.substringBefore("://"); val rest = host.substringAfter("://")
            host = "$scheme://${rest.substringBefore('/')}:${c.port}" + rest.substringAfter('/', "").let { if (it.isBlank()) "" else "/$it" }
        }
        val basePath = c.share.orEmpty().trim('/').let { if (it.isBlank()) "" else "/$it" }
        val remote = path.trim().let { if (it.isBlank() || it == "/") "" else "/${it.trim('/')}" }
        return host + basePath + remote + if (directory && remote.isNotEmpty()) "/" else ""
    }

    private fun parseMultiStatus(xml: String, currentPath: String): List<FileItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(StringReader(xml))
        val result = mutableListOf<FileItem>(); var href: String? = null; var size = 0L; var modified = Date(0); var mime: String? = null; var directory = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name.lowercase()) {
                "response" -> { href=null; size=0; modified=Date(0); mime=null; directory=false }
                "href" -> href = parser.nextText()
                "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0L
                "getlastmodified" -> modified = parseDate(parser.nextText())
                "getcontenttype" -> mime = parser.nextText()
                "collection" -> directory = true
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("response", true)) {
                href?.let { raw ->
                    val decoded = runCatching { URLDecoder.decode(raw.substringBefore('?'), "UTF-8") }.getOrDefault(raw)
                    val name = decoded.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
                    val parent = if (currentPath.isBlank() || currentPath == "/") "/" else "/" + currentPath.trim('/')
                    val itemPath = if (name == "/") parent else if (parent == "/") "/$name" else "$parent/$name"
                    result += FileItem(itemPath, name, if (directory) 0 else size, modified, directory, mime)
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseDate(value: String): Date = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value) ?: Date(0)
    }.getOrDefault(Date(0))
}
