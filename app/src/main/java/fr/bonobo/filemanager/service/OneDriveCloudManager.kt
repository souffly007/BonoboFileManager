package fr.bonobo.filemanager.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import fr.bonobo.filemanager.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OneDriveItem(val id: String, val name: String, val folder: Boolean, val size: Long = 0)
data class OneDriveState(val connected: Boolean = false, val loading: Boolean = false, val items: List<OneDriveItem> = emptyList(), val error: String? = null, val notice: String? = null)

object OneDriveCloudManager {
    private const val CLIENT_ID = "7b69f306-d1b2-4d91-8a49-52c9bdd3c117"
    // Microsoft attend le hash URL-encodé dans la valeur OAuth.
    // Le manifeste Android utilise, lui, la valeur décodée.
    private const val REDIRECT_URI = "msauth://fr.bonobo.filemanager.https.preview/ZvMAMgcAKi5%2Bi3%2Fs7v4ikDuTROM%3D"
    private const val PREFS = "onedrive_oauth"
    private const val TOKEN_KEY = "tokens"
    private val credentials = CredentialStore()
    private val _state = MutableStateFlow(OneDriveState())
    val state = _state.asStateFlow()

    fun isConnected(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(TOKEN_KEY)

    fun startLogin(context: Context) {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("verifier", verifier).apply()
        val uri = Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI).appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", "openid profile offline_access User.Read Files.ReadWrite")
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256").build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun handleCallback(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code") ?: return@withContext
        val verifier = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("verifier", null) ?: return@withContext
        try {
            val json = JSONObject(postForm("https://login.microsoftonline.com/common/oauth2/v2.0/token", mapOf(
                "client_id" to CLIENT_ID, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to REDIRECT_URI, "code_verifier" to verifier, "scope" to "openid profile offline_access User.Read Files.ReadWrite"
            )))
            storeTokens(context, json)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("verifier").apply()
            _state.value = OneDriveState(connected = true, notice = "Connexion OneDrive réussie.")
        } catch (exception: Exception) {
            _state.value = OneDriveState(error = "Connexion OneDrive impossible : ${exception.message}")
        }
    }

    suspend fun listFolder(context: Context, itemId: String? = null) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try {
            val token = accessToken(context) ?: error("Compte Microsoft non connecté")
            val endpoint = if (itemId == null) {
                "https://graph.microsoft.com/v1.0/me/drive/root/children"
            } else {
                "https://graph.microsoft.com/v1.0/me/drive/items/$itemId/children"
            }
            val json = JSONObject(get(endpoint, token))
            val values = json.getJSONArray("value")
            val items = List(values.length()) { index ->
                val entry = values.getJSONObject(index)
                OneDriveItem(entry.getString("id"), entry.getString("name"), entry.has("folder"), entry.optLong("size", 0L))
            }
            _state.value = OneDriveState(true, false, items)
        } catch (exception: Exception) {
            _state.value = OneDriveState(true, false, error = exception.message)
        }
    }

    suspend fun download(context: Context, item: OneDriveItem): String = withContext(Dispatchers.IO) {
        check(!item.folder) { "Un dossier ne peut pas être téléchargé directement" }
        val token = accessToken(context) ?: error("Compte Microsoft non connecté")
        val name = item.name.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bonobo OneDrive"); put(MediaStore.Downloads.IS_PENDING, 1) }
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Impossible de créer le fichier dans Téléchargements")
        try {
            val connection = URL("https://graph.microsoft.com/v1.0/me/drive/items/${item.id}/content").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 15_000; connection.readTimeout = 120_000
            if (connection.responseCode !in 200..299) error("OneDrive (${connection.responseCode})")
            val output = context.contentResolver.openOutputStream(uri) ?: error("Destination inaccessible")
            connection.inputStream.use { input -> output.use { input.copyTo(it) } }
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            "Téléchargements/Bonobo OneDrive/$name"
        } catch (exception: Exception) { context.contentResolver.delete(uri, null, null); throw exception }
    }

    suspend fun upload(context: Context, source: Uri, fileName: String, folderId: String?): String = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Microsoft non connecté")
        val safeName = fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "fichier" }
        val encodedName = Uri.encode(safeName)
        val endpoint = if (folderId == null) {
            "https://graph.microsoft.com/v1.0/me/drive/root:/$encodedName:/content"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$folderId:/$encodedName:/content"
        }
        val connection = URL(endpoint + "?@microsoft.graph.conflictBehavior=rename").openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"; connection.doOutput = true
        connection.connectTimeout = 15_000; connection.readTimeout = 120_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        context.contentResolver.openInputStream(source)?.use { input ->
            connection.outputStream.use { output -> input.copyTo(output) }
        } ?: error("Fichier source inaccessible")
        val response = readResponse(connection, "OneDrive")
        if (response.isBlank()) error("OneDrive n'a pas confirmé l'envoi")
        "OneDrive/$safeName"
    }

    suspend fun createFolder(context: Context, parentId: String?, folderName: String) = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Microsoft non connecté")
        val name = folderName.trim().replace(Regex("[/\\\\]"), "_").ifBlank { error("Nom de dossier vide") }
        val endpoint = if (parentId == null) {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/$parentId/children"
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(JSONObject()
            .put("name", name)
            .put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "rename")
            .toString().toByteArray(Charsets.UTF_8)) }
        readResponse(connection, "OneDrive")
    }

    suspend fun delete(context: Context, item: OneDriveItem) = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: error("Compte Microsoft non connecté")
        val connection = URL("https://graph.microsoft.com/v1.0/me/drive/items/${item.id}").openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        readResponse(connection, "OneDrive")
    }

    fun setNotice(message: String) { _state.value = _state.value.copy(notice = message, error = null) }
    fun setError(message: String) { _state.value = _state.value.copy(error = message) }
    fun disconnect(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply(); _state.value = OneDriveState() }

    private suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null) ?: return@withContext null
        val parts = credentials.decrypt(packed).split('\n')
        val access = parts.getOrNull(0).orEmpty(); val refresh = parts.getOrNull(1).orEmpty()
        val expiresAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (access.isBlank()) return@withContext null
        if (refresh.isBlank() || expiresAt == 0L || expiresAt > System.currentTimeMillis() + 60_000L) return@withContext access
        val json = JSONObject(postForm("https://login.microsoftonline.com/common/oauth2/v2.0/token", mapOf(
            "client_id" to CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to refresh,
            "scope" to "openid profile offline_access User.Read Files.ReadWrite"
        )))
        storeTokens(context, json, refresh)
        json.getString("access_token")
    }

    private fun storeTokens(context: Context, json: JSONObject, previousRefresh: String = "") {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token", previousRefresh)
        val expiry = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
        val packed = credentials.encrypt("$access\n$refresh\n$expiry")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, packed).apply()
    }

    private fun get(endpoint: String, token: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        return readResponse(connection, "OneDrive")
    }

    private fun postForm(endpoint: String, values: Map<String, String>): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = values.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(connection, "Microsoft")
    }

    private fun readResponse(connection: HttpURLConnection, service: String): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("$service ($code): ${body.take(180)}")
        return body
    }
}
