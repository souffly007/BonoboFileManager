package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.nio.file.Files

class FileUtilsTest {

    @Test
    fun `format bytes correctly`() {
        assertEquals("0 o", FileUtils.formatSize(0))
        assertEquals("1 Ko", FileUtils.formatSize(1024))
        assertEquals("1 Mo", FileUtils.formatSize(1024 * 1024))
        assertEquals("1,5 Go", FileUtils.formatSize(3L * 1024 * 1024 * 1024 / 2))
    }

    @Test fun emptyFolderAndUnreadableLocationHaveDifferentResults() {
        val folder = Files.createTempDirectory("bonobo-empty-").toFile()
        val ordinaryFile = Files.createTempFile("bonobo-file-", ".txt").toFile()
        try {
            assertTrue(FileUtils.listFiles(folder).isEmpty())
            assertTrue(runCatching { FileUtils.listFiles(ordinaryFile) }.isFailure)
        } finally { folder.delete(); ordinaryFile.delete() }
    }
}
