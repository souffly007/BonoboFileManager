package fr.bonobo.filemanager.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import fr.bonobo.filemanager.domain.usecase.CopyFileUseCase
import fr.bonobo.filemanager.domain.usecase.DeleteFileUseCase
import fr.bonobo.filemanager.domain.usecase.MoveFileUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.cancel


@AndroidEntryPoint
class FileOperationService : Service() {

    @Inject
    lateinit var copyFileUseCase: CopyFileUseCase

    @Inject
    lateinit var moveFileUseCase: MoveFileUseCase

    @Inject
    lateinit var deleteFileUseCase: DeleteFileUseCase

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val operation = intent?.getStringExtra(EXTRA_OPERATION)
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        val destination = intent?.getStringExtra(EXTRA_DESTINATION)

        scope.launch {
            when (operation) {
                OPERATION_COPY -> {
                    if (source != null && destination != null) {
                        copyFileUseCase(source, destination)
                    }
                }

                OPERATION_MOVE -> {
                    if (source != null && destination != null) {
                        moveFileUseCase(source, destination)
                    }
                }

                OPERATION_DELETE -> {
                    if (source != null) {
                        deleteFileUseCase(source)
                    }
                }
            }

            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DESTINATION = "destination"

        const val OPERATION_COPY = "copy"
        const val OPERATION_MOVE = "move"
        const val OPERATION_DELETE = "delete"
    }
}
