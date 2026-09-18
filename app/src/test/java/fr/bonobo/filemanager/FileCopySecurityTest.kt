package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.FileUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileCopySecurityTest {
    @Test fun copyPreservesExistingFileAndRejectsCanonicalSelfCopy() {
        val root = Files.createTempDirectory("copy-test").toFile()
        try {
            val source = File(root, "source").apply { writeText("source") }
            val destination = File(root, "destination").apply { writeText("keep") }
            assertTrue(runCatching { FileUtils.copy(source, destination) }.isFailure)
            assertEquals("keep", destination.readText())
            assertTrue(runCatching { FileUtils.copy(source, File(root, "./source")) }.isFailure)
            assertEquals("source", source.readText())
            val folder = File(root, "folder").apply { mkdir() }
            assertTrue(runCatching { FileUtils.copy(folder, File(folder, "sub")) }.isFailure)
            val fresh = File(root, "fresh")
            FileUtils.copy(source, fresh)
            assertEquals("source", fresh.readText())
        } finally { root.deleteRecursively() }
    }
}
