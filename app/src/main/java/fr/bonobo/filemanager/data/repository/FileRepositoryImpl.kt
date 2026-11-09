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
import fr.bonobo.filemanager.diagnostic.DeletionTrace
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result as JournalResult
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Rename refuses any existing sibling name before and verifies after (SafMutations.rename).
    private val saf = fr.bonobo.filemanager.util.SafFiles(context, renameWithoutReplacement = true)
    private fun isSaf(path: String) = fr.bonobo.filemanager.util.SafFiles.isSaf(path)
    private fun deleteLocal(path: String) {
        val file = File(path)
        val trace = DeletionTrace(Storage.LOCAL,
            if (file.isDirectory) Kind.DIRECTORY else if (file.exists()) Kind.FILE else Kind.UNKNOWN,
            Mode.PERMANENT)
        trace.start()
        try {
            val accepted = FileUtils.deleteRecursively(file)
            trace.provider(if (accepted) JournalResult.OK else JournalResult.REFUSED)
            val absent = !file.exists()
            trace.verify(if (absent) JournalResult.OK else JournalResult.INDETERMINATE)
            trace.end(if (accepted && absent) JournalResult.OK else JournalResult.INDETERMINATE)
            check(accepted && absent) { "Suppression non confirmée ; actualisez le dossier." }
        } catch (e: Exception) {
            if (file.exists()) trace.verify(JournalResult.INDETERMINATE)
            trace.end(JournalResult.INDETERMINATE)
            throw e
        }
    }


    private suspend fun isRootEnabled(): Boolean {
        return try {
            context.settingsDataStore.data.first()[SettingsKeys.ROOT_ACCESS] ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getFiles(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        if (isSaf(path)) {
            val hidden = context.settingsDataStore.data.first()[SettingsKeys.SHOW_HIDDEN_FILES] ?: false
            return@withContext saf.list(path).filter { hidden || !it.name.startsWith(".") }.map { it.copy(isFavorite = favoriteDao.isFavorite(it.path)) }
        }
        fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_OPEN, JournalResult.START,
            storage = Storage.LOCAL, kind = Kind.DIRECTORY,
            phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.OPEN)
        val showHidden = context.settingsDataStore.data.first()[SettingsKeys.SHOW_HIDDEN_FILES] ?: false
        if (isRootEnabled() && hasRootAccess()) {
            return@withContext getFilesRoot(path, showHidden)
        }

        if (!(File(path).isDirectory && File(path).canRead() && File(path).listFiles() != null)) {
            fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
                fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_PROVIDER_FAILURE,
                JournalResult.IO_ERROR, storage = Storage.LOCAL, kind = Kind.DIRECTORY,
                phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.OPEN)
            error("Stockage indisponible ou accès refusé. Reconnectez le support puis actualisez.")
        }
        fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_OPEN, JournalResult.OK,
            storage = Storage.LOCAL, kind = Kind.DIRECTORY,
            phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.OPEN)
        fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_READ, JournalResult.START,
            storage = Storage.LOCAL, kind = Kind.DIRECTORY,
            phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.READ)
        val files = FileUtils.listFiles(directory = File(path), showHidden = showHidden)
        fr.bonobo.filemanager.diagnostic.Diagnostic.detail(
            fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event.NAV_READ, JournalResult.OK,
            storage = Storage.LOCAL, kind = Kind.DIRECTORY,
            phase = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase.READ)

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
        runCatching { saf.copy(sourcePath, destinationPath); Unit }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    override suspend fun moveFile(sourcePath: String, destinationPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { saf.move(sourcePath, destinationPath) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    override suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        trashOperations.withLock {
        runCatching { if (isSaf(path)) saf.delete(path) else deleteLocal(path) }
        }
    }

    override suspend fun renameFile(path: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isSaf(path)) { saf.rename(path, newName.trim()); return@runCatching Unit }
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
        runCatching { if (isSaf(parentPath)) { saf.create(parentPath, name, android.provider.DocumentsContract.Document.MIME_TYPE_DIR); Unit } else check(FileUtils.createDirectory(File(parentPath), name)) { "Impossible de créer le dossier" } }
    }

    override suspend fun createFile(parentPath: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { if (isSaf(parentPath)) { saf.create(parentPath, name, "application/octet-stream"); Unit } else check(FileUtils.createEmptyFile(File(parentPath), name)) { "Impossible de créer le fichier" } }
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
        trashOperations.withLock {
        runCatching { paths.forEach { path -> if (isSaf(path)) saf.delete(path) else deleteLocal(path) } }
        }
    }

    private val trashOperations = Mutex()
    private val trashDir = File(Environment.getExternalStorageDirectory(), ".bonobo_trash")

    override suspend fun moveToTrash(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        trashOperations.withLock {
        runCatching {
            val source = File(path)
            val trace = DeletionTrace(if (isSaf(path)) Storage.SAF else Storage.LOCAL,
                if (source.isDirectory) Kind.DIRECTORY else if (source.exists()) Kind.FILE else Kind.UNKNOWN,
                Mode.TRASH)
            trace.start()
            try {
            require(!isSaf(path)) { "La corbeille locale ne prend pas en charge SAF. Utilisez Supprimer pour une suppression définitive." }
            check(trashDir.isDirectory || trashDir.mkdirs()) { "Corbeille locale inaccessible : création refusée" }
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
            trace.provider(JournalResult.OK)
            val verified = !source.exists() && destination.exists()
            trace.verify(if (verified) JournalResult.OK else JournalResult.INDETERMINATE)
            trace.end(if (verified) JournalResult.OK else JournalResult.INDETERMINATE)
            check(verified) { "Mise à la corbeille non confirmée ; actualisez." }
            Unit
            } catch (e: Exception) { trace.end(JournalResult.INDETERMINATE); throw e }
        }
        }
    }

    override suspend fun getTrashFiles(): List<FileItem> = withContext(Dispatchers.IO) {
        if (!trashDir.exists()) return@withContext emptyList()
        FileUtils.listFiles(trashDir, showHidden = true).map { item ->
            item.copy(name = fr.bonobo.filemanager.util.TrashPaths.displayName(item.name))
        }
    }

    override suspend fun restoreFromTrash(item: FileItem): Result<Unit> = withContext(Dispatchers.IO) {
        trashOperations.withLock {
        runCatching {
            fr.bonobo.filemanager.util.TrashRestorer(saf, trashDir).restore(item.path)

        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    override suspend fun restoreTrashTo(item: FileItem, parent: String): Result<Unit> = withContext(Dispatchers.IO) {
        trashOperations.withLock {
        runCatching {
            fr.bonobo.filemanager.util.TrashRestorer(saf, trashDir).restoreTo(item.path, parent)

        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    override suspend fun emptyTrash(): Result<Unit> = withContext(Dispatchers.IO) {
        trashOperations.withLock {
        runCatching {
            if (trashDir.exists()) {
                val trace = DeletionTrace(Storage.LOCAL, Kind.DIRECTORY, Mode.TRASH)
                trace.start()
                try {
                check(FileUtils.deleteRecursively(trashDir)) { "Impossible de vider la corbeille" }
                trace.provider(JournalResult.OK)
                val verified = !trashDir.exists()
                trace.verify(if (verified) JournalResult.OK else JournalResult.INDETERMINATE)
                trace.end(if (verified) JournalResult.OK else JournalResult.INDETERMINATE)
                check(verified) { "Effacement de la corbeille non confirmé" }
                } catch (e: Exception) { trace.end(JournalResult.INDETERMINATE); throw e }
            }
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
                    isDirectory = if (isSaf(favorite.path)) favorite.mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR else file.isDirectory,
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
                    isDirectory = if (isSaf(item.path)) item.mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR else file.isDirectory,
                    mimeType = if (isSaf(item.path) || file.exists()) item.mimeType else null,
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
