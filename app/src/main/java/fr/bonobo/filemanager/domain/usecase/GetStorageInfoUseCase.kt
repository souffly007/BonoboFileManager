package fr.bonobo.filemanager.domain.usecase

import android.os.Environment
import fr.bonobo.filemanager.domain.model.StorageInfo
import javax.inject.Inject

class GetStorageInfoUseCase @Inject constructor() {
    operator fun invoke(): StorageInfo {
        val path = Environment.getDataDirectory()
        val totalSpace = path.totalSpace
        val freeSpace = path.freeSpace

        return StorageInfo(
            totalSpace = totalSpace,
            freeSpace = freeSpace,
            usedSpace = totalSpace - freeSpace
        )
    }
}
