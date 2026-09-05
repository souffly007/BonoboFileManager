package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.domain.model.FileItem
import java.io.File
import java.text.DecimalFormat
import java.util.Date

object FileUtils {

    fun listFiles(
        directory: File,
        showHidden: Boolean = false,
    ): List<FileItem> {
        if (!directory.exists()) {
            throw Exception("Le dossier n'existe pas : ${directory.absolutePath}")
        }
        if (!directory.isDirectory) {
            return emptyList()
        }
        val files = try {
            directory.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()

        return files
            .asSequence()
            .filter { file ->
                showHidden || !file.isHidden
            }
            .sortedWith(
                compareByDescending<File> { file ->
                    file.isDirectory
                }.thenBy { file ->
                    file.name.lowercase()
                }
            )
            .map { file ->
                file.toFileItem()
            }
            .toList()
    }

    fun searchFiles(
        directory: File,
        query: String,
        showHidden: Boolean = false
    ): List<FileItem> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        val normalizedQuery = query.trim()

        if (normalizedQuery.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<FileItem>()

        val files = directory.listFiles()
            ?: throw Exception("Impossible d'accéder au dossier pour la recherche")

        files.forEach { file ->

            if (!showHidden && file.isHidden) {
                return@forEach
            }

            if (file.name.contains(
                    other = normalizedQuery,
                    ignoreCase = true
                )
            ) {
                result += file.toFileItem()
            }

            if (file.isDirectory && file.canRead()) {
                result += searchFiles(
                    directory = file,
                    query = normalizedQuery,
                    showHidden = showHidden
                )
            }
        }

        return result
    }

    fun listFilesByType(
        directory: File,
        mimeTypePrefix: String,
        showHidden: Boolean = false
    ): List<FileItem> {
        val result = mutableListOf<FileItem>()
        
        directory.listFiles()?.forEach { file ->
            if (!showHidden && file.isHidden) return@forEach
            
            if (file.isDirectory) {
                if (file.canRead()) {
                    result += listFilesByType(file, mimeTypePrefix, showHidden)
                }
            } else {
                val mime = MimeTypeUtils.fromFile(file)
                if (mime.startsWith(mimeTypePrefix)) {
                    result += file.toFileItem()
                }
            }
        }
        return result
    }

    fun listLargeFiles(
        directory: File,
        minSize: Long,
        showHidden: Boolean = false
    ): List<FileItem> {
        val result = mutableListOf<FileItem>()
        
        directory.listFiles()?.forEach { file ->
            if (!showHidden && file.isHidden) return@forEach
            
            if (file.isDirectory) {
                if (file.canRead()) {
                    result += listLargeFiles(file, minSize, showHidden)
                }
            } else if (file.length() >= minSize) {
                result += file.toFileItem()
            }
        }
        return result
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }

        val units = arrayOf(
            "KB",
            "MB",
            "GB",
            "TB"
        )

        var value = bytes.toDouble()
        var unitIndex = -1

        do {
            value /= 1024.0
            unitIndex++
        } while (
            (value >= 1024.0) &&
            (unitIndex < units.lastIndex)
        )

        return "${
            DecimalFormat("#,##0.##").format(value)
        } ${units[unitIndex]}"
    }

    fun copy(
        source: File,
        destination: File
    ) {
        require(source.exists()) {
            "Le fichier source n'existe pas"
        }

        if (source.absolutePath == destination.absolutePath) {
            error("La source et la destination sont identiques")
        }

        if (source.isDirectory) {
            copyDirectory(
                source = source,
                destination = destination
            )
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(
                target = destination,
                overwrite = true
            )
        }
    }

    private fun copyDirectory(
        source: File,
        destination: File
    ) {
        require(
            !destination.absolutePath.startsWith(
                source.absolutePath + File.separator
            )
        ) {
            "Impossible de copier un dossier dans lui-même"
        }

        destination.mkdirs()

        source.listFiles()?.forEach { child ->
            copy(
                source = child,
                destination = File(
                    destination,
                    child.name
                )
            )
        }
    }

    fun deleteRecursively(
        file: File
    ): Boolean {
        if (!file.exists()) {
            return false
        }

        return file.deleteRecursively()
    }

    fun createDirectory(parent: File, name: String): Boolean {
        val dir = File(parent, name)
        return if (dir.exists()) false else dir.mkdirs()
    }

    fun createEmptyFile(parent: File, name: String): Boolean {
        val file = File(parent, name)
        return if (file.exists()) false else file.createNewFile()
    }

    fun findJunkFiles(directory: File): List<File> {
        val junkExtensions = setOf("tmp", "log", "temp", "cache")
        val junkFiles = mutableListOf<File>()
        
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (file.canRead()) {
                    junkFiles.addAll(findJunkFiles(file))
                }
            } else {
                if (junkExtensions.contains(file.extension.lowercase())) {
                    junkFiles.add(file)
                }
            }
        }
        return junkFiles
    }

    fun findEmptyFolders(directory: File): List<File> {
        val emptyFolders = mutableListOf<File>()
        
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children != null && children.isEmpty()) {
                    emptyFolders.add(file)
                } else if (file.canRead()) {
                    emptyFolders.addAll(findEmptyFolders(file))
                }
            }
        }
        return emptyFolders
    }

    internal fun File.toFileItem(): FileItem {
        return FileItem(
            path = absolutePath,
            name = name,
            size = if (isFile) length() else 0L,
            lastModified = Date(lastModified()),
            isDirectory = isDirectory,
            mimeType = MimeTypeUtils.fromFile(this)
        )
    }
}
