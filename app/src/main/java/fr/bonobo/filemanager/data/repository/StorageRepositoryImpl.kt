package fr.bonobo.filemanager.data.repository

import android.os.Environment
import android.os.StatFs
import fr.bonobo.filemanager.domain.model.StorageInfo
import fr.bonobo.filemanager.domain.repository.IStorageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor() : IStorageRepository {

    override fun getInternalStorageInfo(): StorageInfo {
        val path = Environment
            .getDataDirectory()
            .absolutePath

        val stat = StatFs(path)

        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val used = (total - free).coerceAtLeast(0L)

        return StorageInfo(
            totalSpace = total,
            freeSpace = free,
            usedSpace = used
        )
    }
}
