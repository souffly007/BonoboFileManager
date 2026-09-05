package fr.bonobo.filemanager.domain.model

data class StorageInfo(
    val totalSpace: Long,
    val freeSpace: Long,
    val usedSpace: Long
) {
    val usagePercent: Float
        get() = if (totalSpace > 0) usedSpace.toFloat() / totalSpace.toFloat() else 0f
}
