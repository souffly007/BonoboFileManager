package fr.bonobo.filemanager.data.repository

import android.os.Environment
import android.net.Uri
import android.provider.OpenableColumns
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.dao.*
import fr.bonobo.filemanager.data.local.entity.*
import fr.bonobo.filemanager.data.local.settingsDataStore
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import fr.bonobo.filemanager.util.FileUtils
import fr.bonobo.filemanager.util.SafeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val fileDao: FileDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : IFileRepository {

    private suspend fun isRootEnabled(): Boolean {
        return try {
            context.settingsDataStore.data.first()[SettingsKeys.ROOT_ACCESS] ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getFiles(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val showHidden = context.settingsDataStore.data.first()[SettingsKeys.SHOW_HIDDEN_FILES] ?: false
        if (isRootEnabled() && hasRootAccess()) {
            return@withContext getFilesRoot(path, showHidden)
        }

        val files = FileUtils.listFiles(directory = File(path), showHidden = showHidden)

        fileDao.insertAll(
            files.map { item ->
                FileEntity(
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    lastModified = item.lastModified.time,
                    isDirectory = item.isDirectory,
                    mimeType = item.mimeType
                )
            }
        )

        files.map { item ->
            item.copy(isFavorite = favoriteDao.isFavorite(item.path))
        }
    }

    private fun hasRootAccess(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").start()
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) false
            else process.inputStream.bufferedReader().use { it.readLine()?.contains("uid=0") == true }
        } finally { process.destroy() }
    } catch (_: Exception) { false }

    private fun getFilesRoot(path: String, showHidden: Boolean): List<FileItem> {
        val quoted = SafeFiles.shellQuote(File(path).absolutePath)
        val patterns = if (showHidden) "$quoted/* $quoted/.[!.]* $quoted/..?*" else "$quoted/*"
        val command = """for entry in $patterns; do
            [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
            if [ -d "${'$'}entry" ]; then printf 'd\0%s\0' "${'$'}entry";
            else printf 'f\0%s\0' "${'$'}entry"; fi
            done"""
        val process = ProcessBuilder("su", "-c", command).redirectError(java.lang.ProcessBuilder.Redirect.INHERIT).start()
        return try {
            val fields = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8).split('\u0000') }
            check(process.waitFor() == 0) { "Lecture root impossible" }
            fields.dropLastWhile { it.isEmpty() }.chunked(2).map { pair ->
                require(pair.size == 2) { "Réponse root invalide" }
                val file = File(pair[1])
                FileItem(path = file.absolutePath, name = file.name, size = file.length(),
                    lastModified = Date(file.lastModified()), isDirectory = pair[0] == "d",
                    mimeType = if (pair[0] == "d") null else "application/octet-stream")
            }
        } finally { process.destroy() }
    }

    override suspend fun getFilesByType(mimeTypePrefix: String): List<FileItem> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        FileUtils.listFilesByType(root, mimeTypePrefix)
    }

    override suspend fun getLargeFiles(minSize: Long): List<FileItem> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        FileUtils.listLargeFiles(root, minSize)
    }

    override suspend fun searchFiles(query: String): List<FileItem> = withContext(Dispatchers.IO) {
        val rootDirectory = Environment.getExternalStorageDirectory()
        FileUtils.searchFiles(directory = rootDirectory, query = query).map { item ->
            item.copy(isFavorite = favoriteDao.isFavorite(item.path))
        }
    }

    override suspend fun copyFile(sourcePath: String, destinationPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { FileUtils.copy(source = File(sourcePath), destination = File(destinationPath)) }
    }

    override suspend fun moveFile(sourcePath: String, destinationPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(sourcePath)
            val destination = File(destinationPath)
            FileUtils.copy(source = source, destination = destination)
            check(FileUtils.deleteRecursively(source)) { "Impossible de supprimer le fichier source" }
        }
    }

    override suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { check(FileUtils.deleteRecursively(File(path))) { "Impossible de supprimer le fichier" } }
    }

    override suspend fun renameFile(path: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(newName.isNotBlank()) { "Le nouveau nom est vide" }
            val source = File(path)
            require(source.exists()) { "Le fichier n'existe pas" }
            val parent = source.parentFile ?: error("Dossier parent introuvable")
            val destination = SafeFiles.child(parent, newName.trim())
            require(!destination.exists()) { "Un fichier portant ce nom existe déjà" }
            java.nio.file.Files.move(source.toPath(), destination.toPath())
            Unit
        }
    }

    override suspend fun createFolder(parentPath: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { check(FileUtils.createDirectory(File(parentPath), name)) { "Impossible de créer le dossier" } }
    }

    override suspend fun createFile(parentPath: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { check(FileUtils.createEmptyFile(File(parentPath), name)) { "Impossible de créer le fichier" } }
    }

    override suspend fun getJunkFiles(): List<FileItem> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        FileUtils.findJunkFiles(root).map { with(FileUtils) { it.toFileItem() } }
    }

    override suspend fun getEmptyFolders(): List<FileItem> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        FileUtils.findEmptyFolders(root).map { with(FileUtils) { it.toFileItem() } }
    }

    override suspend fun deleteFiles(paths: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { paths.forEach { path -> check(FileUtils.deleteRecursively(File(path))) { "Suppression impossible : $path" } } }
    }

    private val trashDir = File(Environment.getExternalStorageDirectory(), ".bonobo_trash")

    override suspend fun moveToTrash(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!trashDir.exists()) trashDir.mkdirs()
            val source = File(path)
            val encodedPath = android.util.Base64.encodeToString(
                source.absolutePath.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            var destination = File(trashDir, "trash_${encodedPath}_#_${source.name}")
            var duplicate = 1
            while (SafeFiles.exists(destination)) {
                destination = File(trashDir, "trash_${encodedPath}_#_${source.name} (${duplicate++})")
            }
            java.nio.file.Files.move(source.toPath(), destination.toPath())
            Unit
        }
    }

    override suspend fun getTrashFiles(): List<FileItem> = withContext(Dispatchers.IO) {
        if (!trashDir.exists()) return@withContext emptyList()
        FileUtils.listFiles(trashDir, showHidden = true).map { item ->
            val name = item.name
            val originalName = when {
                name.contains("_#_") -> name.substringAfter("_#_")
                name.startsWith("trash_") -> {
                    val afterTrash = name.substring(6)
                    if (afterTrash.contains("_")) afterTrash.substringAfter("_") else afterTrash
                }
                else -> name
            }
            item.copy(name = originalName)
        }
    }

    override suspend fun restoreFromTrash(item: FileItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileInTrash = File(item.path)
            require(fileInTrash.canonicalFile.parentFile == trashDir.canonicalFile) { "Entrée de corbeille invalide" }
            val fileName = fileInTrash.name
            val encodedPath = when {
                fileName.contains("_#_") -> fileName.substringAfter("trash_").substringBefore("_#_")
                fileName.startsWith("trash_") -> fileName.substring(6).substringBefore("_")
                else -> error("Format de fichier inconnu")
            }
            val originalPath = String(android.util.Base64.decode(encodedPath, android.util.Base64.URL_SAFE))
            val destination = File(originalPath).canonicalFile
            val storage = Environment.getExternalStorageDirectory().canonicalFile.toPath()
            require(destination.toPath().startsWith(storage) && destination.toPath() != storage &&
                !destination.toPath().startsWith(trashDir.canonicalFile.toPath())) { "Chemin de restauration invalide" }
            require(!SafeFiles.exists(destination)) { "La destination existe déjà : restauration annulée" }
            destination.parentFile?.mkdirs()
            java.nio.file.Files.move(fileInTrash.toPath(), destination.toPath())
            Unit
        }
    }

    override suspend fun emptyTrash(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (trashDir.exists()) {
                check(FileUtils.deleteRecursively(trashDir)) { "Impossible de vider la corbeille" }
            }
        }
    }

    override fun observeFavorites(): Flow<List<FileItem>> {
        return favoriteDao.getAllFavorites().map { favorites ->
            favorites.map { favorite ->
                val file = File(favorite.path)
                FileItem(
                    path = favorite.path,
                    name = favorite.name,
                    size = if (file.exists()) file.length() else 0L,
                    lastModified = Date(favorite.addedAt),
                    isDirectory = file.isDirectory,
                    mimeType = favorite.mimeType,
                    isFavorite = true
                )
            }
        }
    }

    override fun observeHistory(): Flow<List<FileItem>> {
        return historyDao.getHistory().map { history ->
            history.map { item ->
                val file = File(item.path)
                FileItem(
                    path = item.path,
                    name = item.name,
                    size = if (file.exists()) file.length() else 0L,
                    lastModified = Date(item.accessedAt),
                    isDirectory = file.isDirectory,
                    mimeType = if (file.exists()) item.mimeType else null,
                    isFavorite = false
                )
            }
        }
    }

    override fun observeBookmarks(): Flow<List<FileItem>> {
        return bookmarkDao.getAllBookmarks().map { bookmarks ->
            bookmarks.map { bookmark ->
                val file = File(bookmark.path)
                FileItem(
                    path = bookmark.path,
                    name = bookmark.name,
                    size = 0L,
                    lastModified = Date(),
                    isDirectory = true,
                    mimeType = null,
                    isFavorite = false
                )
            }
        }
    }

    override suspend fun toggleFavorite(file: FileItem) {
        if (favoriteDao.isFavorite(file.path)) {
            favoriteDao.deleteByPath(file.path)
        } else {
            favoriteDao.insertFavorite(FavoriteEntity(path = file.path, name = file.name, mimeType = file.mimeType))
        }
    }

    override suspend fun addToHistory(file: FileItem) {
        historyDao.insertHistory(HistoryEntity(path = file.path, name = file.name, mimeType = file.mimeType))
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }

    override suspend fun addBookmark(path: String, name: String) {
        bookmarkDao.insertBookmark(BookmarkEntity(path = path, name = name))
    }

    override suspend fun removeBookmark(path: String) {
        bookmarkDao.deleteByPath(path)
    }

    override suspend fun getVaultFiles(): List<FileItem> =
        error("Ouvrez le coffre-fort depuis l'accueil pour vous authentifier")

    override suspend fun importFileToVault(uri: String): Result<String> =
        Result.failure(IllegalStateException("Utilisez l'importation du coffre-fort sécurisé"))
}
