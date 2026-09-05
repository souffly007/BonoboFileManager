package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class RenameFileUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(
        path: String,
        newName: String
    ): Result<Unit> {
        return repository.renameFile(path, newName)
    }
}
