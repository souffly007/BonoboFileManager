package fr.bonobo.filemanager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.bonobo.filemanager.domain.usecase.CopyFileUseCase
import fr.bonobo.filemanager.domain.usecase.DeleteFileUseCase
import fr.bonobo.filemanager.domain.usecase.MoveFileUseCase

class FileOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val copyFileUseCase: CopyFileUseCase,
    private val moveFileUseCase: MoveFileUseCase,
    private val deleteFileUseCase: DeleteFileUseCase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val operation = inputData.getString(KEY_OPERATION)
        val source = inputData.getString(KEY_SOURCE)
        val destination = inputData.getString(KEY_DESTINATION)

        val result = when (operation) {
            OPERATION_COPY -> {
                if (source == null || destination == null) {
                    return Result.failure()
                }
                copyFileUseCase(source, destination)
            }

            OPERATION_MOVE -> {
                if (source == null || destination == null) {
                    return Result.failure()
                }
                moveFileUseCase(source, destination)
            }

            OPERATION_DELETE -> {
                if (source == null) {
                    return Result.failure()
                }
                deleteFileUseCase(source)
            }

            else -> return Result.failure()
        }

        return if (result.isSuccess) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    companion object {
        const val KEY_OPERATION = "operation"
        const val KEY_SOURCE = "source"
        const val KEY_DESTINATION = "destination"

        const val OPERATION_COPY = "copy"
        const val OPERATION_MOVE = "move"
        const val OPERATION_DELETE = "delete"
    }
}
