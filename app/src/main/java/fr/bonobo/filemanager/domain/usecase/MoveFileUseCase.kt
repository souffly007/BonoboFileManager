package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class MoveFileUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(
        source: String,
        destination: String
    ): Result<Unit> {
        return repository.moveFile(source, destination)
    }
}
