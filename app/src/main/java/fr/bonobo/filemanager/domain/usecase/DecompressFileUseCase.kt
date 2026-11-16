package fr.bonobo.filemanager.domain.usecase

import com.github.junrar.Archive
import fr.bonobo.filemanager.util.SafeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import net.lingala.zip4j.ZipFile as SecureZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

class DecompressFileUseCase @Inject constructor() {
    suspend operator fun invoke(zipFile: File, destDir: File, password: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            check(destDir.isDirectory || destDir.mkdirs()) { "Dossier d'extraction inaccessible" }
            val budget = Budget()
            when (zipFile.extension.lowercase()) {
                "rar" -> Archive(zipFile, password).use { archive ->
                    var entry = archive.nextFileHeader()
                    while (entry != null) {
                        val current = entry
                        extract(destDir, current.fileName.trim(), current.isDirectory, budget) {
                            archive.extractFile(current, it)
                        }
                        entry = archive.nextFileHeader()
                    }
                }
                "7z" -> SevenZFile.builder().setFile(zipFile).setPassword(password?.toCharArray()).get().use { archive ->
                    var entry = archive.nextEntry
                    while (entry != null) {
                        val current = entry
                        extract(destDir, current.name, current.isDirectory, budget) { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = archive.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                        entry = archive.nextEntry
                    }
                }
                "tar" -> TarArchiveInputStream(BufferedInputStream(zipFile.inputStream())).use { archive ->
                    var entry = archive.nextTarEntry
                    while (entry != null) {
                        val current = entry
                        extract(destDir, current.name, current.isDirectory, budget) { output ->
                            if (!current.isDirectory) archive.copyTo(output)
                        }
                        entry = archive.nextTarEntry
                    }
                }
                "gz" -> {
                    val outputName = zipFile.name.removeSuffix(".gz")
                    extract(destDir, outputName, false, budget) { output ->
                        GzipCompressorInputStream(BufferedInputStream(zipFile.inputStream())).use { it.copyTo(output) }
                    }
                }
                else -> if (!password.isNullOrBlank()) {
                    SecureZipFile(zipFile, password.toCharArray()).use { archive ->
                        archive.fileHeaders.forEach { entry ->
                            extract(destDir, entry.fileName, entry.isDirectory, budget) { output ->
                                archive.getInputStream(entry).use { it.copyTo(output) }
                            }
                        }
                    }
                } else ZipFile(zipFile).use { archive ->
                    val entries = archive.entries
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        require(!entry.isUnixSymlink) { "Lien symbolique interdit dans l'archive" }
                        extract(destDir, entry.name, entry.isDirectory, budget) { output ->
                            archive.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private class Budget {
        var entries = 0
        var bytes = 0L
        fun consume(count: Int) {
            check(!Thread.currentThread().isInterrupted) { "Extraction interrompue" }
            bytes += count
            check(bytes <= 4L * 1024 * 1024 * 1024) { "Archive trop volumineuse : limite de 4 Gio décompressés" }
        }
    }

    private fun extract(root: File, name: String, directory: Boolean, budget: Budget,
                        write: (OutputStream) -> Unit) {
        check(++budget.entries <= 10_000) { "Archive trop volumineuse : limite de 10 000 entrées" }
        val destination = SafeFiles.archiveChild(root, name)
        if (directory) {
            check(destination.isDirectory || destination.mkdirs()) { "Impossible de créer un dossier" }
            return
        }
        require(!SafeFiles.exists(destination)) { "Un fichier existe déjà : $name" }
        check(destination.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Dossier inaccessible" }
        val temporary = File.createTempFile(".extraction-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { target ->
                write(object : OutputStream() {
                    override fun write(value: Int) { budget.consume(1); target.write(value) }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        budget.consume(length)
                        target.write(buffer, offset, length)
                    }
                })
            }
            SafeFiles.archiveChild(root, name)
            SafeFiles.publish(temporary, destination)
        } finally {
            temporary.delete()
        }
    }
}
