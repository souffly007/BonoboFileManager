package fr.bonobo.filemanager.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import fr.bonobo.filemanager.util.SafeFiles
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Streams into the destination storage, without an additional 5 GB cache copy. */
class RemoteDownloadTarget private constructor(val output: OutputStream,
    private val finish: () -> String, private val discard: () -> Boolean) {
    fun commit() = finish()
    fun abort(): Boolean { runCatching { output.close() }; return discard() }

    companion object {
        fun create(context: Context, name: String, mime: String, chosen: Uri?, size: Long): RemoteDownloadTarget {
            SafeFiles.child(context.filesDir, name) // Reject remote names that could escape a directory.
            if (chosen != null) {
                val output = context.contentResolver.openOutputStream(chosen, "w") ?: error("Destination inaccessible")
                return RemoteDownloadTarget(output, {
                    releaseGrant(context, chosen)
                    "le dossier choisi ($name)"
                }, { deleteDocument(context, chosen) })
            }
            if (Build.VERSION.SDK_INT >= 29) {
                check(size <= 0 || Environment.getExternalStorageDirectory().usableSpace >= size) { "Espace libre insuffisant dans Téléchargements" }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Impossible de créer le fichier dans Téléchargements")
                try {
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Destination inaccessible")
                    return RemoteDownloadTarget(output, {
                        check(context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }, null, null) == 1) { "Impossible de finaliser le téléchargement" }
                        "Téléchargements/$name"
                    }, { runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) })
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            check(directory.isDirectory || directory.mkdirs()) { "Dossier Téléchargements inaccessible" }
            check(size <= 0 || directory.usableSpace >= size) { "Espace libre insuffisant dans Téléchargements" }
            var file = SafeFiles.child(directory, name)
            val base = name.substringBeforeLast('.', name)
            val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var index = 1
            var output: OutputStream
            while (true) {
                try {
                    output = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    break
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    file = SafeFiles.child(directory, "$base (${index++})$suffix")
                }
            }
            val selected = file
            return RemoteDownloadTarget(output, { "Téléchargements/${selected.name}" }, { selected.delete() })
        }
        fun deleteDocument(context: Context, uri: Uri): Boolean {
            val result = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            releaseGrant(context, uri)
            return result
        }
        private fun releaseGrant(context: Context, uri: Uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
}
