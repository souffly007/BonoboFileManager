package fr.bonobo.filemanager.domain.usecase

import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class DecompressFileUseCase @Inject constructor() {
    suspend operator fun invoke(zipFile: File, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            
            val ext = zipFile.extension.lowercase()
            when (ext) {
                "rar" -> extractRar(zipFile, destDir)
                "7z" -> extract7z(zipFile, destDir)
                else -> extractZip(zipFile, destDir)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(file: File, destDir: File) {
        ZipFile(file).use { zipFile ->
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun extractRar(file: File, destDir: File) {
        Archive(file).use { archive ->
            var fh = archive.nextFileHeader()
            while (fh != null) {
                val outFile = File(destDir, fh.fileName.trim())
                if (fh.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        archive.extractFile(fh, fos)
                    }
                }
                fh = archive.nextFileHeader()
            }
        }
    }

    private fun extract7z(file: File, destDir: File) {
        SevenZFile(file).use { sevenZFile ->
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
    }
}
