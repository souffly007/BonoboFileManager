package fr.bonobo.filemanager.domain.model

import java.util.Date

data class FileItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Date,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val isFavorite: Boolean = false
)
