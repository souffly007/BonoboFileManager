package fr.bonobo.filemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    val path: String,
    val name: String,
    val mimeType: String?,
    val addedAt: Long = System.currentTimeMillis()
)