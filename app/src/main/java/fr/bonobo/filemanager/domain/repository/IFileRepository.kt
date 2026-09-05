package fr.bonobo.filemanager.domain.repository

import fr.bonobo.filemanager.domain.model.FileItem
import kotlinx.coroutines.flow.Flow

interface IFileRepository {

    suspend fun getFiles(path: String): List<FileItem>
    suspend fun getFilesByType(mimeTypePrefix: String): List<FileItem>
    suspend fun getLargeFiles(minSize: Long): List<FileItem>
    suspend fun searchFiles(query: String): List<FileItem>

    suspend fun copyFile(sourcePath: String, destinationPath: String): Result<Unit>
    suspend fun moveFile(sourcePath: String, destinationPath: String): Result<Unit>
    suspend fun deleteFile(path: String): Result<Unit>
    suspend fun renameFile(path: String, newName: String): Result<Unit>
    suspend fun createFolder(parentPath: String, name: String): Result<Unit>
    suspend fun createFile(parentPath: String, name: String): Result<Unit>

    suspend fun getJunkFiles(): List<FileItem>
    suspend fun getEmptyFolders(): List<FileItem>
    suspend fun deleteFiles(paths: List<String>): Result<Unit>

    suspend fun moveToTrash(path: String): Result<Unit>
    suspend fun getTrashFiles(): List<FileItem>
    suspend fun restoreFromTrash(item: FileItem): Result<Unit>
    suspend fun emptyTrash(): Result<Unit>

    fun observeFavorites(): Flow<List<FileItem>>
    fun observeHistory(): Flow<List<FileItem>>
    fun observeBookmarks(): Flow<List<FileItem>>

    suspend fun toggleFavorite(file: FileItem)
    suspend fun addToHistory(file: FileItem)
    suspend fun clearHistory()
    
    suspend fun addBookmark(path: String, name: String)
    suspend fun removeBookmark(path: String)
    
    // Coffre-fort
    suspend fun getVaultFiles(): List<FileItem>
}
