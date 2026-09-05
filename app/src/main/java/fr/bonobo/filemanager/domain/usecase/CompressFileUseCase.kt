package fr.bonobo.filemanager.domain.usecase

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class CompressFileUseCase @Inject constructor() {

    suspend operator fun invoke(
        sources: List<String>,
        outputZip: String
    ): Result<Unit> {
        return runCatching {
            ZipOutputStream(File(outputZip).outputStream()).use { zip ->
                sources.forEach { sourcePath ->
                    val source = File(sourcePath)

                    if (source.isDirectory) {
                        addDirectory(
                            root = source,
                            current = source,
                            zip = zip
                        )
                    } else {
                        addFile(
                            root = source.parentFile ?: source,
                            file = source,
                            zip = zip
                        )
                    }
                }
            }
        }
    }

    private fun addDirectory(
        root: File,
        current: File,
        zip: ZipOutputStream
    ) {
        current.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                addDirectory(root, child, zip)
            } else {
                addFile(root, child, zip)
            }
        }
    }

    private fun addFile(
        root: File,
        file: File,
        zip: ZipOutputStream
    ) {
        val entryName = file
            .relativeTo(root)
            .path
            .replace(File.separatorChar, '/')

        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }
}
