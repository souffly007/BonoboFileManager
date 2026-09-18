package fr.bonobo.filemanager.util

import android.webkit.MimeTypeMap
import java.io.File

object MimeTypeUtils {

    fun fromFile(file: File): String {
        val extension = file.extension.lowercase()

        val known = mapOf(
            "pdf" to "application/pdf", "epub" to "application/epub+zip",
            "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar", "gz" to "application/gzip", "mkv" to "video/x-matroska",
            "flac" to "audio/flac", "m4a" to "audio/mp4", "opus" to "audio/opus", "webm" to "video/webm"
        )
        known[extension]?.let { return it }

        return MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: if (file.isDirectory) {
                "inode/directory"
            } else {
                "application/octet-stream"
            }
    }

    fun isImage(mimeType: String?): Boolean =
        mimeType?.startsWith("image/") == true

    fun isVideo(mimeType: String?): Boolean =
        mimeType?.startsWith("video/") == true

    fun isText(mimeType: String?): Boolean =
        mimeType?.startsWith("text/") == true

    fun isPdf(mimeType: String?): Boolean =
        mimeType == "application/pdf"

    fun isArchive(file: File): Boolean = file.extension.lowercase() in setOf("zip", "rar", "7z", "tar", "gz")
}
