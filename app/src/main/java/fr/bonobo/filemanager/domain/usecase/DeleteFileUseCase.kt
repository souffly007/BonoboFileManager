package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class DeleteFileUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(path: String): Result<Unit> {
        return repository.deleteFile(path)
    }
}
