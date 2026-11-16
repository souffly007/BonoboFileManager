package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class SearchFilesUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(query: String): List<FileItem> {
        if (query.isBlank()) return emptyList()
        return repository.searchFiles(query)
    }
}