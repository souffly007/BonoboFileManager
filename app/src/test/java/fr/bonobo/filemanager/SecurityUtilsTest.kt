package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.SecurityUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SecurityUtilsTest {
    @Test
    fun encryptDecryptRoundTripAndRejectWrongPassword() {
        val directory = Files.createTempDirectory("bonobo-security").toFile()
        try {
            val originalBytes = "données confidentielles".toByteArray()
            val source = directory.resolve("document.txt").apply { writeBytes(originalBytes) }
            val encrypted = SecurityUtils.encrypt(source, "mot-de-passe-solide")
            assertTrue(encrypted.exists())
            assertFalse(encrypted.readBytes().contentEquals(originalBytes))

            source.delete()
            var rejected = false
            try {
                SecurityUtils.decrypt(encrypted, "mauvais-mot-de-passe")
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue(rejected)
            assertFalse(source.exists())

            val decrypted = SecurityUtils.decrypt(encrypted, "mot-de-passe-solide")
            assertArrayEquals(originalBytes, decrypted.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun passwordHashIsSaltedAndVerifiable() {
        val first = SecurityUtils.hashPassword("mot-de-passe-solide")
        val second = SecurityUtils.hashPassword("mot-de-passe-solide")
        assertFalse(first == second)
        assertTrue(SecurityUtils.verifyPassword("mot-de-passe-solide", first))
        assertFalse(SecurityUtils.verifyPassword("incorrect", first))
    }
}
