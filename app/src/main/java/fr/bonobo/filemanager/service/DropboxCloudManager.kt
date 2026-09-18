package fr.bonobo.filemanager.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import fr.bonobo.filemanager.util.CredentialStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class DropboxItem(val name: String, val path: String, val folder: Boolean, val size: Long = 0, val apiPath: String = path, val id: String? = null)
data class DropboxState(val connected: Boolean = false, val loading: Boolean = false, val items: List<DropboxItem> = emptyList(), val error: String? = null, val notice: String? = null)

object DropboxCloudManager {
    private const val APP_KEY = "a4pj0x994ilv7o6"
    private const val REDIRECT_URI = "db-a4pj0x994ilv7o6://1/connect"
    private const val PREFS = "dropbox_oauth"
    private const val TOKEN_KEY = "tokens"
    private val credentialStore = CredentialStore()
    private val _state = MutableStateFlow(DropboxState())
    val state = _state.asStateFlow()

    fun isConnected(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(TOKEN_KEY)

    fun startLogin(context: Context) {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("verifier", verifier).apply()
        val uri = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", APP_KEY).appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("scope", "files.metadata.read files.metadata.write files.content.read files.content.write")
            .appendQueryParameter("token_access_type", "offline").appendQueryParameter("redirect_uri", REDIRECT_URI).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun handleCallback(context: Context, uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        val verifier = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("verifier", null) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = postForm("https://api.dropboxapi.com/oauth2/token", mapOf("code" to code, "grant_type" to "authorization_code", "client_id" to APP_KEY, "code_verifier" to verifier, "redirect_uri" to REDIRECT_URI))
                val json = JSONObject(body)
                val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 14_400L) * 1000L
                val packed = credentialStore.encrypt(json.getString("access_token") + "\n" + json.optString("refresh_token") + "\n" + expiresAt)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, packed).remove("verifier").apply()
                _state.value = DropboxState(connected = true)
            }.onFailure { _state.value = DropboxState(error = "Connexion Dropbox impossible : ${it.message}") }
        }
    }

    suspend fun listRoot(context: Context) { listFolder(context, "") }

    suspend fun listFolder(context: Context, path: String) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        runCatching {
            val token = accessToken(context) ?: error("Compte Dropbox non connecté")
            val json = JSONObject(postJson("https://api.dropboxapi.com/2/files/list_folder", token, JSONObject().put("path", path).put("recursive", false)))
            val result = json.getJSONArray("entries")
            List(result.length()) { i -> result.getJSONObject(i).let {
                DropboxItem(
                    name = it.getString("name"),
                    path = it.getString("path_display"),
                    folder = it.getString(".tag") == "folder",
                    size = it.optLong("size", 0),
                    apiPath = it.optString("path_lower", it.getString("path_display")),
                    id = it.optString("id").takeIf { value -> value.isNotBlank() }
                )
            } }
        }.onSuccess { _state.value = DropboxState(true, false, it) }.onFailure { _state.value = DropboxState(true, false, error = it.message) }
    }

    suspend fun download(context: Context, item: DropboxItem): String = withContext(Dispatchers.IO) {
        check(!item.folder) { "Un dossier ne peut pas être téléchargé directement" }
        val token = accessToken(context) ?: error("Compte Dropbox non connecté")
        val safeName = item.name.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(safeName))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bonobo Dropbox")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossible de créer le fichier dans Téléchargements")
        try {
            val downloadTarget = item.id ?: item.apiPath.trim().let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
            val temporaryLinkResponse = postJson(
                "https://api.dropboxapi.com/2/files/get_temporary_link",
                token,
                JSONObject().put("path", downloadTarget)
            )
            val temporaryLink = JSONObject(temporaryLinkResponse).getString("link")
            val connection = URL(temporaryLink).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            if (connection.responseCode !in 200..299) {
                val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(details).optString("error_summary") }.getOrNull().orEmpty()
                error("Dropbox (${connection.responseCode})${if (message.isNotBlank()) ": $message" else ""}")
            }
            val output = context.contentResolver.openOutputStream(uri) ?: error("Destination inaccessible")
            connection.inputStream.use { input -> output.use { input.copyTo(it) } }
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            "Téléchargements/Bonobo Dropbox/$safeName"
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun upload(context: Context, source: Uri, fileName: String, folderPath: String): String = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Dropbox non connecté")
        val safeName = fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val target = (folderPath.trimEnd('/') + "/" + safeName).ifBlank { "/$safeName" }
        val connection = URL("https://content.dropboxapi.com/2/files/upload").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 15_000; connection.readTimeout = 120_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("Dropbox-API-Arg", JSONObject()
            .put("path", if (target.startsWith('/')) target else "/$target")
            .put("mode", "add").put("autorename", true).toString())
        context.contentResolver.openInputStream(source)?.use { input ->
            connection.outputStream.use { output -> input.copyTo(output) }
        } ?: error("Fichier source inaccessible")
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Dropbox ($code): ${body.take(180)}")
        "Dropbox/${if (target.startsWith('/')) target.drop(1) else target}"
    }

    suspend fun createFolder(context: Context, folderPath: String, folderName: String) = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Dropbox non connecté")
        val name = folderName.trim().replace(Regex("[/\\\\]"), "_").ifBlank { error("Nom de dossier vide") }
        val path = (folderPath.trimEnd('/') + "/" + name).ifBlank { "/$name" }
        postJson("https://api.dropboxapi.com/2/files/create_folder_v2", token,
            JSONObject().put("path", if (path.startsWith('/')) path else "/$path").put("autorename", true))
    }

    suspend fun delete(context: Context, item: DropboxItem) = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Dropbox non connecté")
        val path = item.id ?: item.apiPath
        postJson("https://api.dropboxapi.com/2/files/delete_v2", token, JSONObject().put("path", path))
    }

    private fun mimeType(name: String): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    fun setNotice(message: String) { _state.value = _state.value.copy(loading = false, notice = message, error = null) }
    fun setError(message: String) { _state.value = _state.value.copy(loading = false, error = message) }

    fun disconnect(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply(); _state.value = DropboxState() }

    private suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null)
            ?: return@withContext null
        val parts = credentialStore.decrypt(packed).split('\n')
        val access = parts.getOrNull(0).orEmpty()
        val refresh = parts.getOrNull(1).orEmpty()
        val expiresAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (access.isBlank()) return@withContext null
        if (refresh.isBlank() || expiresAt == 0L || expiresAt > System.currentTimeMillis() + 60_000L) {
            return@withContext access
        }
        val json = JSONObject(postForm("https://api.dropboxapi.com/oauth2/token", mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to APP_KEY
        )))
        val renewedAccess = json.getString("access_token")
        val renewedRefresh = json.optString("refresh_token", refresh)
        val renewedExpiry = System.currentTimeMillis() + json.optLong("expires_in", 14_400L) * 1000L
        val renewedPacked = credentialStore.encrypt("$renewedAccess\n$renewedRefresh\n$renewedExpiry")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, renewedPacked).apply()
        renewedAccess
    }
    private fun postForm(endpoint: String, values: Map<String, String>): String = request(endpoint, "application/x-www-form-urlencoded", values.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }, null)
    private fun postJson(endpoint: String, token: String, body: JSONObject): String = request(endpoint, "application/json", body.toString(), token)
    private fun request(endpoint: String, contentType: String, body: String, token: String?): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", contentType); token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val result = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val details = result
            val message = runCatching {
                val json = JSONObject(details)
                json.optString("error_summary").ifBlank { json.optString("error") }
            }.getOrNull().orEmpty().ifBlank { details.take(180) }
            error("Dropbox (${connection.responseCode})${if (message.isNotBlank()) ": $message" else ""}")
        }
        return result
    }
}
