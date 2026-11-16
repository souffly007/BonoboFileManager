package fr.bonobo.filemanager.util

import android.os.Environment
import android.os.StatFs
import fr.bonobo.filemanager.domain.model.StorageInfo

object StorageUtils {

    fun internalStorageInfo(): StorageInfo {
        val stat = StatFs(
            Environment.getDataDirectory().absolutePath
        )

        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong

        val used = total - free

        return StorageInfo(
            totalSpace = total,
            freeSpace = free,
            usedSpace = used
        )
    }
}
