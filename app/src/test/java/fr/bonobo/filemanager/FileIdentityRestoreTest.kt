package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Shared identity (ArtifactCleanup + TrashRestorer): no fileKey / creationTime, survives remount. */
class FileIdentityRestoreTest {
    private fun directory() = Files.createTempDirectory("identity-restore-").toFile()

    /** Replace a file by an identical copy: new inode, same content and modification date (= remount / reboot). */
    private fun renumber(file: File) {
        val copy = File(file.parentFile, file.name + ".tmp")
        Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        Files.move(copy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun digest(path: String) = IntegrityVerifier.fingerprint(LocalIntegrityAccess, path)

    /** Same receipt logic as TrashRestorer, with a local copy and a volume table for the test. */
    private suspend fun restore(receipts: RestoreReceipts, source: File, target: File,
        volumeOf: (String) -> Pair<String?, File?>?, force: Boolean = false) {
        val identity = FileIdentity.local(source, volumeOf)
        receipts.restore(source.canonicalPath, identity.encode(), { digest(source.path) },
            { require(!target.exists()) { "La destination existe déjà" }; target.path },
            exists = { path ->
                val file = File(path)
                // like SafFiles.restoreDestinationExists: an unreadable parent is NOT absence
                check(file.exists() || file.parentFile?.canRead() == true && file.parentFile?.isDirectory == true) { "Destination précédente inaccessible" }
                file.exists()
            },
            verify = { path, expected -> check(digest(path) == expected) },
            copy = { path -> Files.copy(source.toPath(), File(path).toPath(), StandardCopyOption.COPY_ATTRIBUTES); path },
            compare = { stored -> TrashRestorer.compare(stored, identity) { source.lastModified() } }, force = force)
    }

    @Test fun identityIgnoresInodeAndRoundTrips() {
        val root = directory()
        try {
            val file = File(root, "photo.jpg").apply { writeText("content") }
            val volumeOf = { _: String -> "DC20-EA13" to root }
            val before = FileIdentity.local(file, volumeOf)
            renumber(file)
            val after = FileIdentity.local(file, volumeOf)
            assertNull(FileIdentity.compare(before, after))
            assertEquals("DC20-EA13", before.volume); assertEquals("photo.jpg", before.relativePath)
            assertEquals(before, FileIdentity.decode(before.encode()))
            // FAT: 2 s granularity tolerated, a real modification is not.
            assertNull(FileIdentity.compare(before, before.copy(modified = before.modified!! + 1_500)))
            assertEquals(FileIdentity.Field.MODIFIED, FileIdentity.compare(before, before.copy(modified = before.modified!! + 60_000))!!.field)
            val grown = FileIdentity.compare(before, before.copy(size = 99))!!
            assertEquals(FileIdentity.Field.SIZE, grown.field); assertEquals(7L, grown.expected); assertEquals(99L, grown.found)
            assertEquals(FileIdentity.Field.DOCUMENT,
                FileIdentity.compare(FileIdentity(document = "a:1"), FileIdentity(document = "a:2"))!!.field)
        } finally { root.deleteRecursively() }
    }

    @Test fun internalRestoreSurvivesRebootWithoutDuplicate() = runBlocking {
        val root = directory()
        try {
            val internal = File(root, "emulated0").apply { mkdirs() }
            val volumeOf = { _: String -> null to internal } // primary volume: uuid null
            val trash = File(internal, ".bonobo_trash").apply { mkdir() }
            val entry = File(trash, "trash_x_#_notes.txt").apply { writeText("notes") }
            val receipts = RestoreReceipts(File(root, "receipts"))
            restore(receipts, entry, File(internal, "notes.txt"), volumeOf)
            renumber(entry) // reboot: new FUSE device / inode numbers
            val elsewhere = File(internal, "Download/notes.txt").apply { parentFile!!.mkdirs() }
            val second = runCatching { restore(receipts, entry, elsewhere, volumeOf) }.exceptionOrNull()
            assertTrue("$second", second is AlreadyRestoredException)
            assertFalse(elsewhere.exists()) // no second copy
        } finally { root.deleteRecursively() }
    }

    @Test fun usbRestoreAfterRemount() = runBlocking {
        val root = directory()
        try {
            val internal = File(root, "emulated0").apply { mkdirs() }
            val usb = File(root, "DC20-EA13").apply { mkdirs() }
            val volumeOf = { path: String -> if (path.startsWith(usb.canonicalPath)) "DC20-EA13" to usb else null to internal }
            val trash = File(internal, ".bonobo_trash").apply { mkdir() }
            val entry = File(trash, "trash_x_#_film.mkv").apply { writeText("film") }
            val receipts = RestoreReceipts(File(root, "receipts"))
            val target = File(usb, "Films/film.mkv").apply { parentFile!!.mkdirs() }
            restore(receipts, entry, target, volumeOf)
            // Key pulled out: the previous target cannot be observed → refused, nothing copied elsewhere.
            val unplugged = File(root, "unplugged")
            assertTrue(usb.renameTo(unplugged))
            val other = File(internal, "film.mkv")
            assertTrue(runCatching { restore(receipts, entry, other, volumeOf) }.isFailure)
            assertFalse(other.exists())
            // Remounted at the same place, inodes renumbered: recognised as already restored.
            assertTrue(unplugged.renameTo(usb))
            renumber(target); renumber(entry)
            assertTrue(runCatching { restore(receipts, entry, other, volumeOf) }.exceptionOrNull() is AlreadyRestoredException)
            assertFalse(other.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun mismatchAsksThenRestoreAnyway() = runBlocking {
        val root = directory()
        try {
            val volumeOf = { _: String -> null to root }
            val entry = File(root, "trash_x_#_doc.txt").apply { writeText("v1") }
            val receipts = RestoreReceipts(File(root, "receipts"))
            restore(receipts, entry, File(root, "first.txt"), volumeOf)
            entry.writeText("version 2, longer")
            val target = File(root, "second.txt")
            val error = runCatching { restore(receipts, entry, target, volumeOf) }.exceptionOrNull()
            assertTrue("$error", error is RestoreMismatchException)
            val mismatch = (error as RestoreMismatchException).mismatch
            assertEquals(FileIdentity.Field.SIZE, mismatch.field)
            assertEquals(2L, mismatch.expected); assertEquals(17L, mismatch.found)
            assertFalse(target.exists()) // not silent, not copied
            restore(receipts, entry, target, volumeOf, force = true) // « Restaurer quand même »
            assertEquals("version 2, longer", target.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyReceiptIsMatchedByTimeOnly() {
        val current = FileIdentity(volume = "primary", relativePath = "x", size = 3, modified = 1_763_100_000_000)
        val time = java.time.Instant.ofEpochMilli(1_763_100_000_000).toString()
        assertNull(TrashRestorer.compare("(dev=fd00,ino=1):$time", current) { 1_763_100_000_000 })
        // Another inode is irrelevant; another time is a real difference.
        assertNull(TrashRestorer.compare("(dev=99,ino=42):$time", current) { 1_763_100_001_000 })
        assertEquals(FileIdentity.Field.MODIFIED, TrashRestorer.compare("(dev=fd00,ino=1):$time", current) { 1_763_200_000_000 }!!.field)
        assertEquals(FileIdentity.Field.FORMAT, TrashRestorer.compare("garbage", current) { null }!!.field)
    }
}
