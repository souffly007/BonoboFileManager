package fr.bonobo.filemanager.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object IncomingFiles {
    const val MAX_BYTES = 256L * 1024 * 1024
    val extensions = mapOf(
        "application/pdf" to "pdf", "application/epub+zip" to "epub",
        "text/plain" to "txt", "text/csv" to "csv", "application/json" to "json",
        "application/zip" to "zip", "application/vnd.rar" to "rar", "application/x-7z-compressed" to "7z",
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
        "video/mp4" to "mp4", "audio/mpeg" to "mp3",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt", "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp"
    )
    fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.replace('%', '_').take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Document"
    fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long = MAX_BYTES): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Fichier trop volumineux pour l'ouverture temporaire (256 Mio)" }
            output.write(buffer, 0, count)
        }
        return total
    }
    fun grantedAccess(flags: Int): Int = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    fun canPersist(flags: Int): Boolean = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && grantedAccess(flags) != 0
    fun receive(context: Context, intent: Intent): List<File> {
        val files = mutableListOf<File>()
        try {
            var total = 0L
            uris(intent).forEach { uri ->
                val file = snapshot(context, uri)
                files += file
                total += file.length()
                require(total <= 512L * 1024 * 1024) { "Partage trop volumineux (512 Mio au total)" }
            }
            return files
        } catch (e: Exception) { files.forEach { it.delete(); it.parentFile?.delete() }; throw e }
    }
    @Suppress("DEPRECATION")
    fun uris(intent: Intent): List<Uri> {
        val values = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(values::add)
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(values::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(values::addAll)
            else -> return emptyList()
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(values::add) }
        require(values.size <= 32) { "Trop de fichiers reçus (maximum 32)" }
        require(values.all { it.scheme == "content" }) { "Seules les URI content:// sont acceptées" }
        return values.distinct()
    }
    /** Private snapshot: temporary grants are consumed while the receiving Activity is alive. */
    fun snapshot(context: Context, uri: Uri): File {
        require(uri.scheme == "content") { "URI invalide" }
        val provider = context.packageManager.resolveContentProvider(uri.authority ?: "", 0)
        require(provider?.applicationInfo?.uid != android.os.Process.myUid()) { "Une application externe ne peut pas demander l'ouverture des fichiers privés de Bonobo" }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val extension = extensions[mime] ?: error("Format non pris en charge : ${mime ?: "inconnu"}")
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Document"
        val dir = File(context.cacheDir, "received/${java.util.UUID.randomUUID()}")
        check(dir.mkdirs()) { "Impossible de préparer le fichier reçu" }
        val file = File(dir, safeName(display).substringBeforeLast('.', safeName(display)) + "." + extension)
        try {
            (resolver.openInputStream(uri) ?: error("Flux inaccessible")).use { input ->
                file.outputStream().use { output ->
                    copyBounded(input, output)
                }
            }
            return file
        } catch (e: Exception) { file.delete(); dir.delete(); throw e }
    }
}
