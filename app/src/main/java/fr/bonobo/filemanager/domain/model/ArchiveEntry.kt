package fr.bonobo.filemanager.domain.model

data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)
