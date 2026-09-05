package fr.bonobo.filemanager.util

import android.webkit.MimeTypeMap
import java.io.File

object MimeTypeUtils {

    fun fromFile(file: File): String {
        val extension = file.extension.lowercase()

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
}