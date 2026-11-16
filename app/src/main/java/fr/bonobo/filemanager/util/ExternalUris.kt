package fr.bonobo.filemanager.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * URI handed to another app (« Ouvrir avec », « Partager », installation d'APK) for a local path.
 *
 * FileProvider only covers the internal shared storage and the app's private folders (file_paths.xml):
 * for a file on a USB key (/storage/DC20-EA13/…) it throws « Failed to find configured root ». Such a file
 * is handed over through Android's own providers instead:
 *  1. the externalstorage document URI under a tree the user already granted (« Sélectionner la racine
 *     de la clé »): content://com.android.externalstorage.documents/tree/…/document/DC20-EA13%3A…;
 *  2. otherwise its MediaStore entry on that volume (indexed media files);
 *  3. otherwise a clear message — never a crash.
 * A « .bonobo-part » is an incomplete copy: it is never handed to another app.
 */
object ExternalUris {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** A persisted tree grant: its URI and its tree document id (« DC20-EA13: », « DC20-EA13:Films »). */
    data class Tree(val uri: String, val documentId: String)
    /** A mounted volume: UUID and root directory. */
    data class Volume(val uuid: String, val root: String)

    sealed interface Route {
        /** Covered by file_paths.xml. */
        data object Provider : Route
        /** Document [documentId] inside the granted [tree]. */
        data class Document(val tree: String, val documentId: String) : Route
        /** MediaStore lookup on volume [volume] (lower-case UUID, as MediaStore names it). */
        data class Media(val volume: String, val path: String) : Route
        data class Unavailable(val message: String) : Route
    }

    class OpenFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    const val PARTIAL_MESSAGE = "Copie incomplète (« .bonobo-part ») : elle ne peut pas être ouverte ni partagée. " +
        "Supprimez-la ou consultez « Fichiers partiels »."
    const val NO_GRANT_MESSAGE = "Ouverture impossible : Android n’autorise pas encore le partage des fichiers de cette clé " +
        "avec d’autres applications. Ouvrez « Stockage USB / OTG », touchez « Sélectionner la racine de la clé », puis réessayez."

    fun isPartial(name: String) = name.endsWith(OperationArtifacts.PART_SUFFIX)

    private fun under(path: String, root: String): Boolean {
        val base = root.trimEnd('/')
        return path == base || path.startsWith("$base/")
    }

    /** « DC20-EA13:Films/a.mkv » for /storage/DC20-EA13/Films/a.mkv. Null if [path] is not on [volume]. */
    fun documentId(path: String, volume: Volume): String? {
        if (!under(path, volume.root)) return null
        val relative = path.removePrefix(volume.root.trimEnd('/')).trimStart('/')
        return "${volume.uuid}:$relative"
    }

    /** Does the tree [treeId] contain the document [documentId]? Same volume (case-insensitive), path prefix. */
    fun covers(treeId: String, documentId: String): Boolean {
        val treeVolume = treeId.substringBefore(':', ""); val docVolume = documentId.substringBefore(':', "")
        if (treeVolume.isEmpty() || !treeVolume.equals(docVolume, ignoreCase = true)) return false
        val treePath = treeId.substringAfter(':').trim('/')
        val docPath = documentId.substringAfter(':').trim('/')
        return treePath.isEmpty() || docPath == treePath || docPath.startsWith("$treePath/")
    }

    /**
     * Pure routing, unit-tested. [path] canonical; [providerRoots]: canonical roots of file_paths.xml
     * (shared storage, files, cache); [volumes]: mounted removable volumes; [trees]: persisted read grants.
     */
    fun route(path: String, providerRoots: List<String>, volumes: List<Volume>, trees: List<Tree>): Route {
        if (isPartial(File(path).name)) return Route.Unavailable(PARTIAL_MESSAGE)
        if (providerRoots.any { under(path, it) }) return Route.Provider
        val volume = volumes.filter { under(path, it.root) }.maxByOrNull { it.root.length }
            ?: return Route.Unavailable("Ouverture impossible : emplacement non partageable ($path).")
        val id = documentId(path, volume)!!
        // The narrowest grant that contains the file (a folder grant works as well as the root).
        trees.filter { covers(it.documentId, id) }.maxByOrNull { it.documentId.length }
            ?.let { return Route.Document(it.uri, "${it.documentId.substringBefore(':')}:${id.substringAfter(':')}") }
        return Route.Media(volume.uuid.lowercase(), path)
    }

    /** Content URI of [path] that can be granted to another app, or [OpenFailure] with a French message. */
    fun uri(context: Context, path: String): Uri {
        if (SafFiles.isSaf(path)) {
            if (isPartial(Uri.parse(path).lastPathSegment.orEmpty())) throw OpenFailure(PARTIAL_MESSAGE)
            return Uri.parse(path)
        }
        val file = File(path)
        if (!file.exists()) throw OpenFailure("Fichier introuvable : actualisez le dossier.")
        val canonical = file.canonicalPath
        val roots = listOfNotNull(android.os.Environment.getExternalStorageDirectory(), context.filesDir, context.cacheDir)
            .map { it.canonicalPath }
        val volumes = UsbVolumes.all(context).filter { it.mounted && !it.primary && it.uuid != null && it.directory != null }
            .map { Volume(it.uuid!!, it.directory!!.canonicalPath) }
        val trees = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.authority == EXTERNAL_STORAGE && DocumentsContract.isTreeUri(it.uri) }
            .mapNotNull { grant -> runCatching { Tree(grant.uri.toString(), DocumentsContract.getTreeDocumentId(grant.uri)) }.getOrNull() }
        return when (val route = route(canonical, roots, volumes, trees)) {
            Route.Provider -> try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: IllegalArgumentException) { throw OpenFailure(NO_GRANT_MESSAGE, e) }
            is Route.Document -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(route.tree), route.documentId)
            is Route.Media -> media(context, route) ?: throw OpenFailure(NO_GRANT_MESSAGE)
            is Route.Unavailable -> throw OpenFailure(route.message)
        }
    }

    private fun media(context: Context, route: Route.Media): Uri? = runCatching {
        val collection = MediaStore.Files.getContentUri(route.volume)
        context.contentResolver.query(collection, arrayOf(android.provider.BaseColumns._ID),
            "${MediaStore.MediaColumns.DATA}=?", arrayOf(route.path), null)?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }.getOrNull()

    private fun type(context: Context, uri: Uri, mime: String?): String =
        mime?.takeIf { it.isNotBlank() && it != "*/*" } ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: MimeTypeUtils.fromFile(File(uri.lastPathSegment.orEmpty()))

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private fun failureMessage(e: Throwable, action: String): String = when (e) {
        is OpenFailure -> e.message ?: "$action impossible."
        is ActivityNotFoundException -> "Aucune application installée ne peut ouvrir ce type de fichier."
        is SecurityException -> "$action impossible : accès refusé par Android. Sélectionnez à nouveau la racine de la clé dans « Stockage USB / OTG »."
        else -> "$action impossible : ${e.message ?: "erreur inconnue"}"
    }

    /** « Ouvrir avec » : an external app opens the file. Never throws. */
    fun open(context: Context, path: String, mime: String?) {
        runCatching {
            val uri = uri(context, path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type(context, uri, mime))
                clipData = ClipData.newRawUri("Document", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Ouvrir avec").apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { toast(context, failureMessage(it, "Ouverture")) }
    }

    /** « Partager ». Never throws. */
    fun share(context: Context, path: String, mime: String?) {
        runCatching {
            val uri = uri(context, path)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = type(context, uri, mime).takeIf { it != "*/*" } ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Document", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Partager avec").apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { toast(context, failureMessage(it, "Partage")) }
    }

    /** APK installation (package installer). Never throws. */
    fun install(context: Context, path: String) {
        runCatching {
            val uri = uri(context, path)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { toast(context, failureMessage(it, "Installation")) }
    }
}
