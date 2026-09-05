package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.repository.IFileRepository
import fr.bonobo.filemanager.domain.usecase.DeleteFileUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCaseTest {

    @Test
    fun `delete use case delegates to repository`() = runTest {
        val repository = object : IFileRepository {
            override suspend fun getFiles(path: String) = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun getFilesByType(mimeTypePrefix: String) = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun getLargeFiles(minSize: Long) = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun searchFiles(query: String) = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun copyFile(sourcePath: String, destinationPath: String) = Result.success(Unit)
            override suspend fun moveFile(sourcePath: String, destinationPath: String) = Result.success(Unit)
            override suspend fun deleteFile(path: String) = Result.success(Unit)
            override suspend fun renameFile(path: String, newName: String) = Result.success(Unit)
            override suspend fun createFolder(parentPath: String, name: String) = Result.success(Unit)
            override suspend fun createFile(parentPath: String, name: String) = Result.success(Unit)
            override suspend fun getJunkFiles() = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun getEmptyFolders() = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun deleteFiles(paths: List<String>) = Result.success(Unit)
            override suspend fun moveToTrash(path: String) = Result.success(Unit)
            override suspend fun getTrashFiles() = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
            override suspend fun restoreFromTrash(item: fr.bonobo.filemanager.domain.model.FileItem) = Result.success(Unit)
            override suspend fun emptyTrash() = Result.success(Unit)
            override fun observeFavorites() = kotlinx.coroutines.flow.emptyFlow<List<fr.bonobo.filemanager.domain.model.FileItem>>()
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<fr.bonobo.filemanager.domain.model.FileItem>>()
            override fun observeBookmarks() = kotlinx.coroutines.flow.emptyFlow<List<fr.bonobo.filemanager.domain.model.FileItem>>()
            override suspend fun toggleFavorite(file: fr.bonobo.filemanager.domain.model.FileItem) {}
            override suspend fun addToHistory(file: fr.bonobo.filemanager.domain.model.FileItem) {}
            override suspend fun clearHistory() {}
            override suspend fun addBookmark(path: String, name: String) {}
            override suspend fun removeBookmark(path: String) {}
            override suspend fun getVaultFiles() = emptyList<fr.bonobo.filemanager.domain.model.FileItem>()
        }

        val result = DeleteFileUseCase(repository)("/tmp/demo.txt")

        assertTrue(result.isSuccess)
    }
}
