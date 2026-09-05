package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class GetFilesUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(path: String): List<FileItem> {
        return repository.getFiles(path)
    }
}
