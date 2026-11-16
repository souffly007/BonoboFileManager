package fr.bonobo.filemanager.domain.usecase

import com.github.junrar.Archive
import fr.bonobo.filemanager.domain.model.ArchiveEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import net.lingala.zip4j.ZipFile as SecureZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import javax.inject.Inject

class ListArchiveEntriesUseCase @Inject constructor() {
    suspend operator fun invoke(file: File, password: String? = null): Result<List<ArchiveEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            when (file.extension.lowercase()) {
                "rar" -> Archive(file, password).use { archive ->
                    buildList { var e = archive.nextFileHeader(); while (e != null) { add(ArchiveEntry(e.fileName.trim(), e.isDirectory, e.unpSize)); e = archive.nextFileHeader() } }
                }
                "7z" -> SevenZFile.builder().setFile(file).setPassword(password?.toCharArray()).get().use { archive ->
                    buildList { var e = archive.nextEntry; while (e != null) { add(ArchiveEntry(e.name, e.isDirectory, e.size)); e = archive.nextEntry } }
                }
                "tar" -> TarArchiveInputStream(BufferedInputStream(file.inputStream())).use { archive ->
                    buildList { var e = archive.nextTarEntry; while (e != null) { add(ArchiveEntry(e.name, e.isDirectory, e.size)); e = archive.nextTarEntry } }
                }
                "gz" -> GzipCompressorInputStream(BufferedInputStream(file.inputStream())).use { ArchiveEntry(file.name.removeSuffix(".gz"), false, -1L) }.let { listOf(it) }
                else -> if (!password.isNullOrBlank()) {
                    SecureZipFile(file, password.toCharArray()).use { archive ->
                        archive.fileHeaders.map { e -> ArchiveEntry(e.fileName, e.isDirectory, e.uncompressedSize) }
                    }
                } else ZipFile(file).use { archive ->
                    buildList { val entries = archive.entries; while (entries.hasMoreElements()) { val e = entries.nextElement(); add(ArchiveEntry(e.name, e.isDirectory, e.size)) } }
                }
            }
        }
    }
}
