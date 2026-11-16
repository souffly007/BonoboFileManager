package fr.bonobo.filemanager.domain.usecase

import fr.bonobo.filemanager.domain.repository.IFileRepository
import javax.inject.Inject

class CopyFileUseCase @Inject constructor(
    private val repository: IFileRepository
) {
    suspend operator fun invoke(
        source: String,
        destination: String
    ): Result<Unit> {
        return repository.copyFile(source, destination)
    }
}
