package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {

    @Test
    fun `format bytes correctly`() {
        assertEquals("0 B", FileUtils.formatSize(0))
        assertEquals("1 KB", FileUtils.formatSize(1024))
        assertEquals("1 MB", FileUtils.formatSize(1024 * 1024))
    }
}
