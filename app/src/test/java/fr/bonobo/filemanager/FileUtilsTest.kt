package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test fun wildcardSearchMatchesExtensions() {
        val txt = FileUtils.nameMatcher("*.txt")!!
        assertTrue(txt("notes.txt")); assertTrue(txt("NOTES.TXT"))
        assertFalse(txt("notes.txt.bak")); assertFalse(txt("txt"))
        val multi = FileUtils.nameMatcher("*.zip, *.apk")!!
        assertTrue(multi("a.zip")); assertTrue(multi("b.APK")); assertFalse(multi("c.txt"))
        val any = FileUtils.nameMatcher("*.*")!!
        assertTrue(any("a.b")); assertFalse(any("Makefile"))
        val q = FileUtils.nameMatcher("img_??.jpg")!!
        assertTrue(q("IMG_01.jpg")); assertFalse(q("img_1.jpg"))
        val plain = FileUtils.nameMatcher("rapport")!!
        assertTrue(plain("Mon Rapport final.pdf"))
        assertTrue(FileUtils.nameMatcher("   ") == null)
    }

}
