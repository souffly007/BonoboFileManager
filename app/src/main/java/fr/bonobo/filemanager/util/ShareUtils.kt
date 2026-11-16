package fr.bonobo.filemanager.util

import android.content.Context
import java.io.File

/**
 * « Partager ». Local files of the shared storage go through FileProvider; files on a USB key through
 * their externalstorage / MediaStore URI ([ExternalUris]) — FileProvider does not cover removable volumes.
 * Never throws: a failure is shown as a short message.
 */
object ShareUtils {
    fun sharePath(context: Context, path: String, mimeType: String?) = ExternalUris.share(context, path, mimeType)

    fun shareFile(context: Context, file: File, mimeType: String?) = ExternalUris.share(context, file.path, mimeType)
}
