package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.nio.file.Files

class FileUtilsTest {

    @Test
    fun `format bytes correctly`() {
        assertEquals("0 B", FileUtils.formatSize(0))
        assertEquals("1 KB", FileUtils.formatSize(1024))
        assertEquals("1 MB", FileUtils.formatSize(1024 * 1024))
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
