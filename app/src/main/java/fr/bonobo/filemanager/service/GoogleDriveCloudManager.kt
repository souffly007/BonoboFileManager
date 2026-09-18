package fr.bonobo.filemanager.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class GoogleDriveItem(
    val id: String,
    val name: String,
    val folder: Boolean,
    val size: Long = 0L,
    val mimeType: String = ""
)
data class GoogleDriveState(val connected: Boolean = false, val loading: Boolean = false, val items: List<GoogleDriveItem> = emptyList(), val error: String? = null, val notice: String? = null)

object GoogleDriveCloudManager {
    const val REQUEST_CODE = 4501
    private const val CLIENT_ID = "788635179123-u76dusmn91bphjaijivpqq1tsngvcpcc.apps.googleusercontent.com"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val PREFS = "google_drive_oauth"
    private const val EMAIL_KEY = "account_email"
    private val _state = MutableStateFlow(GoogleDriveState())
    val state = _state.asStateFlow()

    fun isConnected(context: Context): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(EMAIL_KEY)

    fun startLogin(context: Context) {
        val activity = context as? Activity ?: error("Connexion Google indisponible")
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        activity.startActivityForResult(GoogleSignIn.getClient(activity, options).signInIntent, REQUEST_CODE)
    }

    fun handleSignInResult(context: Context, data: Intent?) {
        runCatching {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EMAIL_KEY, account.email).apply()
            _state.value = GoogleDriveState(connected = true, notice = "Connexion Google Drive réussie.")
        }.onFailure { _state.value = GoogleDriveState(error = "Connexion Google Drive impossible : ${it.message}") }
    }

    suspend fun listFolder(context: Context, folderId: String? = null) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try {
            val parent = folderId ?: "root"
            val query = "'$parent' in parents and trashed = false"
            val endpoint = "https://www.googleapis.com/drive/v3/files" +
                    "?q=${Uri.encode(query)}&spaces=drive&orderBy=folder,name" +
                    "&fields=files(id,name,mimeType,size)"
            val json = JSONObject(get(context, endpoint))
            val files = json.optJSONArray("files") ?: org.json.JSONArray()
            val items = List(files.length()) { index ->
                val file = files.getJSONObject(index)
                val mimeType = file.optString("mimeType")
                GoogleDriveItem(
                    id = file.getString("id"),
                    name = file.getString("name"),
                    folder = mimeType == "application/vnd.google-apps.folder",
                    size = file.optLong("size", 0L),
                    mimeType = mimeType
                )
            }
            _state.value = GoogleDriveState(true, false, items)
        } catch (e: Exception) {
            _state.value = GoogleDriveState(true, false, error = e.message)
        }
    }

    suspend fun download(context: Context, item: GoogleDriveItem): String = withContext(Dispatchers.IO) {
        val originalName = item.name.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val name = exportedFileName(originalName, item.mimeType)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType(name))
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Bonobo Google Drive")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val destination = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible de créer le fichier dans Téléchargements")
        try {
            val connection = authenticated(context, downloadUrl(item))
            if (connection.responseCode !in 200..299) error("Google Drive (${connection.responseCode})")
            val output = context.contentResolver.openOutputStream(destination) ?: error("Destination inaccessible")
            connection.inputStream.use { input -> output.use { input.copyTo(it) } }
            if (android.os.Build.VERSION.SDK_INT >= 29) context.contentResolver.update(destination, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            "Téléchargements/Bonobo Google Drive/$name"
        } catch (e: Exception) {
            context.contentResolver.delete(destination, null, null)
            throw e
        }
    }

    suspend fun upload(context: Context, source: Uri, fileName: String, folderId: String?) = withContext(Dispatchers.IO) {
        val name = fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val metadata = JSONObject().put("name", name)
        if (folderId != null) metadata.put("parents", org.json.JSONArray().put(folderId))
        val boundary = "bonobo-${UUID.randomUUID()}"
        val connection = authenticated(context, "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        context.contentResolver.openInputStream(source)?.use { input ->
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                input.copyTo(output)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
        } ?: error("Fichier source inaccessible")
        readResponse(connection, "Google Drive")
    }

    suspend fun createFolder(context: Context, parentId: String?, folderName: String) = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("name", folderName.trim()).put("mimeType", "application/vnd.google-apps.folder")
        if (parentId != null) metadata.put("parents", org.json.JSONArray().put(parentId))
        val connection = authenticated(context, "https://www.googleapis.com/drive/v3/files")
        connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(metadata.toString().toByteArray()) }
        readResponse(connection, "Google Drive")
    }

    suspend fun delete(context: Context, item: GoogleDriveItem) = withContext(Dispatchers.IO) {
        val connection = authenticated(context, "https://www.googleapis.com/drive/v3/files/${item.id}")
        connection.requestMethod = "DELETE"
        readResponse(connection, "Google Drive")
    }

    fun setNotice(message: String) { _state.value = _state.value.copy(notice = message, error = null) }
    fun setError(message: String) { _state.value = _state.value.copy(error = message) }
    fun disconnect(context: Context) {
        GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _state.value = GoogleDriveState()
    }

    private suspend fun token(context: Context): String = withContext(Dispatchers.IO) {
        val account: GoogleSignInAccount = GoogleSignIn.getLastSignedInAccount(context) ?: error("Compte Google non connecté")
        GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_SCOPE")
    }

    private suspend fun authenticated(context: Context, endpoint: String): HttpURLConnection {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 120_000
        connection.setRequestProperty("Authorization", "Bearer ${token(context)}")
        return connection
    }

    private suspend fun get(context: Context, endpoint: String): String = runBlockingRequest(authenticated(context, endpoint), "GET")

    private fun runBlockingRequest(connection: HttpURLConnection, method: String, service: String = "Google Drive"): String {
        connection.requestMethod = method
        return readResponse(connection, service)
    }

    private fun readResponse(connection: HttpURLConnection, service: String): String {
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("$service ($code): ${body.take(180)}")
        return body
    }

    private fun mimeType(name: String): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun downloadUrl(item: GoogleDriveItem): String {
        val exportMime = exportMimeType(item.mimeType, item.name)
        return if (exportMime != null) {
            "https://www.googleapis.com/drive/v3/files/${Uri.encode(item.id)}/export?mimeType=${Uri.encode(exportMime)}"
        } else {
            "https://www.googleapis.com/drive/v3/files/${Uri.encode(item.id)}?alt=media"
        }
    }

    private fun exportMimeType(googleMimeType: String, name: String): String? {
        return when (googleMimeType) {
            "application/vnd.google-apps.document" -> when {
                name.endsWith(".odt", ignoreCase = true) -> "application/vnd.oasis.opendocument.text"
                name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            "application/vnd.google-apps.spreadsheet" -> when {
                name.endsWith(".csv", ignoreCase = true) -> "text/csv"
                name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }
            "application/vnd.google-apps.presentation" -> when {
                name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                else -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            }
            else -> null
        }
    }

    private fun exportedFileName(name: String, googleMimeType: String): String {
        if (!googleMimeType.startsWith("application/vnd.google-apps.")) return name
        if (name.contains('.')) return name
        return when (googleMimeType) {
            "application/vnd.google-apps.spreadsheet" -> "$name.xlsx"
            "application/vnd.google-apps.presentation" -> "$name.pptx"
            else -> "$name.docx"
        }
    }
}
