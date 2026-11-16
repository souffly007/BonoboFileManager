package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.SafeFiles
import fr.bonobo.filemanager.util.SecurityUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecurityRegressionTest {
    private fun inDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("bonobo-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun roundTripAndEmptyFile() = inDirectory { root ->
        for (size in listOf(0, 1, 100_000)) {
            val file = File(root, "file-$size")
            val bytes = ByteArray(size) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val encrypted = SecurityUtils.encrypt(file, "password123")
            assertTrue(file.delete())
            assertArrayEquals(bytes, SecurityUtils.decrypt(encrypted, "password123").readBytes())
        }
    }

    @Test fun wrongPasswordAndTamperingPublishNothing() = inDirectory { root ->
        val source = File(root, "document").apply { writeText("private document") }
        val encrypted = SecurityUtils.encrypt(source, "password123")
        source.delete()
        assertTrue(runCatching { SecurityUtils.decrypt(encrypted, "wrong") }.isFailure)
        assertFalse(source.exists())
        val original = encrypted.readBytes()
        for (index in listOf(0, 4, 20, original.lastIndex)) {
            val corrupt = original.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            encrypted.writeBytes(corrupt)
            assertTrue(runCatching { SecurityUtils.decrypt(encrypted, "password123") }.isFailure)
            assertFalse(source.exists())
            assertEquals(listOf(encrypted.name), root.list()!!.toList())
        }
        encrypted.writeBytes(original.copyOf(10))
        assertTrue(runCatching { SecurityUtils.decrypt(encrypted, "password123") }.isFailure)
        assertFalse(source.exists())
    }

    @Test fun existingDestinationAndSymlinkAreNeverOverwritten() = inDirectory { root ->
        val source = File(root, "document").apply { writeText("keep me") }
        val encrypted = SecurityUtils.encrypt(source, "password123")
        assertTrue(runCatching { SecurityUtils.decrypt(encrypted, "password123") }.isFailure)
        assertEquals("keep me", source.readText())
        val original = encrypted.readBytes()
        assertTrue(runCatching { SecurityUtils.encrypt(source, "password123") }.isFailure)
        assertArrayEquals(original, encrypted.readBytes())
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        assertTrue(runCatching { SafeFiles.writeNew(link) { it.write(0) } }.isFailure)
        assertEquals("keep me", source.readText())
    }

    @Test fun failedImportLeavesNoCiphertext() = inDirectory { root ->
        val target = File(root, "document.crypt")
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("provider disconnected")
        }
        assertTrue(runCatching { SecurityUtils.encryptTo(input, target, "password123") }.isFailure)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun rejectTraversalAndExternalSymlinks() = inDirectory { root ->
        for (name in listOf("../outside", "a/../../outside", "a\\..\\outside", "/absolute", "C:\\file", "..")) {
            assertTrue(name, runCatching { SafeFiles.archiveChild(root, name) }.isFailure)
        }
        assertEquals(File(root, "folder/file"), SafeFiles.archiveChild(root, "folder/file"))
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            assertTrue(runCatching { SafeFiles.archiveChild(root, "escape/file") }.isFailure)
        } finally { outside.deleteRecursively() }
    }

    @Test fun newNamesRejectParentComponents() = inDirectory { root ->
        for (name in listOf("", ".", "..", "../secret", "a/b", "a\\b")) {
            assertTrue(runCatching { SafeFiles.child(root, name) }.isFailure)
        }
    }

    @Test fun hashSaltAndLegacyPasswords() {
        val one = SecurityUtils.hashPassword("password123")
        val two = SecurityUtils.hashPassword("password123")
        assertNotEquals(one, two)
        assertTrue(SecurityUtils.verifyPassword("password123", one))
        assertFalse(SecurityUtils.verifyPassword("wrong", one))
        assertTrue(SecurityUtils.verifyPassword("old", "old"))
        assertFalse(SecurityUtils.verifyPassword("x", "v1\$210000\$invalid\$invalid"))
    }
}
