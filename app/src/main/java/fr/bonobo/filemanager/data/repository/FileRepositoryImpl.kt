package fr.bonobo.filemanager.data.repository

import android.os.Environment
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.dao.*
import fr.bonobo.filemanager.data.local.entity.*
import fr.bonobo.filemanager.data.local.settingsDataStore
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import fr.bonobo.filemanager.util.FileUtils
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
        if (isRootEnabled() && hasRootAccess()) {
            return@withContext getFilesRoot(path)
        }

        val files = FileUtils.listFiles(directory = File(path))

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

    private fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            line?.contains("uid=0") ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun getFilesRoot(path: String): List<FileItem> {
        val result = mutableListOf<FileItem>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -ld $path/*"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String? = reader.readLine()
            
            while (line != null) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 8) {
                    val fullPath = parts.last()
                    val name = fullPath.substringAfterLast("/")
                    val isDir = line.startsWith("d")
                    
                    result.add(FileItem(
                        path = fullPath,
                        name = name,
                        size = 0,
                        lastModified = Date(),
                        isDirectory = isDir,
                        mimeType = if (isDir) null else "application/octet-stream"
                    ))
                }
                line = reader.readLine()
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
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
            val destination = File(parent, newName.trim())
            require(!destination.exists()) { "Un fichier portant ce nom existe déjà" }
            check(source.renameTo(destination)) { "Impossible de renommer le fichier" }
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
        runCatching { paths.forEach { path -> FileUtils.deleteRecursively(File(path)) } }
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
            val destination = File(trashDir, "trash_${encodedPath}_#_${source.name}")
            check(source.renameTo(destination)) { "Échec du déplacement vers la corbeille" }
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
            val fileName = fileInTrash.name
            val encodedPath = when {
                fileName.contains("_#_") -> fileName.substringAfter("trash_").substringBefore("_#_")
                fileName.startsWith("trash_") -> fileName.substring(6).substringBefore("_")
                else -> error("Format de fichier inconnu")
            }
            val originalPath = String(android.util.Base64.decode(encodedPath, android.util.Base64.URL_SAFE))
            val destination = File(originalPath)
            destination.parentFile?.mkdirs()
            check(fileInTrash.renameTo(destination)) { "Échec de la restauration" }
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

    private val vaultDir = File(Environment.getExternalStorageDirectory(), ".bonobo_vault")

    override suspend fun getVaultFiles(): List<FileItem> = withContext(Dispatchers.IO) {
        if (!vaultDir.exists()) vaultDir.mkdirs()
        FileUtils.listFiles(vaultDir, showHidden = true)
    }
}
