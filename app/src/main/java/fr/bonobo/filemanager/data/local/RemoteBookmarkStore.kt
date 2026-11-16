package fr.bonobo.filemanager.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Dossier réseau mis en favori (aucun mot de passe n'est stocké ici). */
data class RemoteBookmark(
    val connectionId: Long,
    val connectionName: String,
    val path: String,
    val name: String
) {
    val key: String get() = "$connectionId|$path"
}

/**
 * Favoris de dossiers réseau (SMB, SFTP, WebDAV, FTPS), stockés en local dans des
 * SharedPreferences. Le mot de passe reste dans la table des connexions (chiffré).
 */
@Singleton
class RemoteBookmarkStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("remote_bookmarks", Context.MODE_PRIVATE)

    private val _bookmarks = MutableStateFlow(load())
    val bookmarks: StateFlow<List<RemoteBookmark>> = _bookmarks.asStateFlow()

    fun isBookmarked(connectionId: Long, path: String): Boolean =
        _bookmarks.value.any { it.key == "$connectionId|${normalize(path)}" }

    /** Ajoute le dossier s'il n'est pas déjà en favori, sinon le retire. */
    @Synchronized
    fun toggle(connectionId: Long, connectionName: String, path: String, displayName: String) {
        val normalized = normalize(path)
        val key = "$connectionId|$normalized"
        val current = _bookmarks.value
        val updated = if (current.any { it.key == key }) {
            current.filterNot { it.key == key }
        } else {
            current + RemoteBookmark(connectionId, connectionName, normalized, displayName)
        }
        save(updated)
    }

    @Synchronized
    fun remove(bookmark: RemoteBookmark) {
        save(_bookmarks.value.filterNot { it.key == bookmark.key })
    }

    private fun save(list: List<RemoteBookmark>) {
        _bookmarks.value = list
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().apply {
                put("connectionId", it.connectionId)
                put("connectionName", it.connectionName)
                put("path", it.path)
                put("name", it.name)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun load(): List<RemoteBookmark> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            RemoteBookmark(
                connectionId = o.getLong("connectionId"),
                connectionName = o.optString("connectionName"),
                path = o.getString("path"),
                name = o.optString("name")
            )
        }
    }.getOrDefault(emptyList())

    private fun normalize(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        return if (trimmed.isEmpty()) "/" else if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
