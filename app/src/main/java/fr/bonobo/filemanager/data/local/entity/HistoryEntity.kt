package fr.bonobo.filemanager.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "history",
    primaryKeys = ["path"]
)
data class HistoryEntity(
    val path: String,
    val name: String,
    val mimeType: String?,
    val accessedAt: Long = System.currentTimeMillis()
)

