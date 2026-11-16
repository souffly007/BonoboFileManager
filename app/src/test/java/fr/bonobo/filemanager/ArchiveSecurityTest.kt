package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.usecase.DecompressFileUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveSecurityTest {
    @Test fun zipTraversalIsRejectedAndNormalZipWorks() = runBlocking {
        val root = Files.createTempDirectory("archive-test").toFile()
        try {
            val zip = File(root, "test.zip")
            val output = File(root, "extracted")
            fun archive(name: String) {
                ZipOutputStream(zip.outputStream()).use {
                    it.putNextEntry(ZipEntry(name)); it.write("payload".toByteArray()); it.closeEntry()
                }
            }
            archive("../outside.txt")
            assertTrue(DecompressFileUseCase()(zip, output).isFailure)
            assertFalse(File(root, "outside.txt").exists())
            archive("nested/inside.txt")
            assertTrue(DecompressFileUseCase()(zip, output).isSuccess)
            assertEquals("payload", File(output, "nested/inside.txt").readText())
            File(output, "nested/inside.txt").writeText("preserve")
            assertTrue(DecompressFileUseCase()(zip, output).isFailure)
            assertEquals("preserve", File(output, "nested/inside.txt").readText())
        } finally { root.deleteRecursively() }
    }
}
