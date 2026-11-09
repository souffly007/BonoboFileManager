package fr.bonobo.filemanager.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareUtils {

    fun sharePath(context: Context, path: String, mimeType: String?) {
        if (!SafFiles.isSaf(path)) { shareFile(context, File(path), mimeType); return }
        val uri = android.net.Uri.parse(path)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Partager avec")) }
            .onFailure { android.widget.Toast.makeText(context, "Partage impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    fun shareFile(
        context: Context,
        file: File,
        mimeType: String?
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Partager avec")
        )
    }
}
