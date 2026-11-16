package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.OperationArtifacts
import fr.bonobo.filemanager.util.PendingCleanup
import fr.bonobo.filemanager.util.SafFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Bug 14/11 n°2: app killed during a restoration to the key → the .bonobo-part is cleaned at the next start. */
class InterruptedTransferCleanupTest {
    private fun directory() = Files.createTempDirectory("interrupted-").toFile()

    /** Same rules as SafFiles.cleanupAccess for local paths. */
    private val local = object : ArtifactCleanup.Access {
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        override fun list(parent: String) = (File(parent).listFiles() ?: throw IOException("absent")).map {
            ArtifactCleanup.Node(it.path, it.name, it.isDirectory, it.canonicalPath, if (it.isFile) it.length() else null, it.lastModified())
        }
        override fun matches(entry: OperationArtifacts.Entry, node: ArtifactCleanup.Node) =
            File(node.path).canonicalPath == File(entry.document ?: entry.path).canonicalPath
        override fun delete(entry: OperationArtifacts.Entry) = Files.delete(File(entry.path).toPath())
    }

    /** What SafFiles.createLocal does: receipt (intent) BEFORE the file exists, then the confirmed receipt. */
    private fun createPart(operation: OperationArtifacts, parent: File, name: String, volume: String?, create: Boolean = true): File {
        val file = File(parent, SafFiles.partName(name, operation.id.take(8)))
        operation.record(OperationArtifacts.Entry(file.path, parent.path, file.name, false, "unconfirmed:x", false, true, volume))
        if (!create) return file // killed between the receipt and the creation
        check(file.createNewFile())
        operation.record(OperationArtifacts.Entry(file.path, parent.path, file.name, false, file.canonicalPath, true, true,
            volume, operation = operation.id, document = file.canonicalPath))
        return file
    }

    @Test fun partOfAKilledRestorationIsCleanedAtNextStart() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            // Restoration of the 2 GB film to the key: receipt written « en cours », then the part, then data.
            val operation = OperationArtifacts(receipts)
            OperationArtifacts.markRunning(operation.id)
            operation.begin()
            val part = createPart(operation, key, "film.mkv", "DC20-EA13")
            part.writeBytes(ByteArray(64 * 1024) { 7 })
            // The app is killed: no end(), no cleanup; a new process knows no running operation.
            OperationArtifacts.markFinished(operation.id)
            val film = File(key, "film.mkv")
            assertFalse(film.exists())

            assertEquals(listOf(operation.id), OperationArtifacts.interrupted(receipts))
            assertEquals(1, PendingCleanup.adoptInterrupted(receipts))
            assertTrue(OperationArtifacts.interrupted(receipts).isEmpty()) // adopted once
            assertTrue(OperationArtifacts.pending(receipts).all { it.deferred })

            val summary = PendingCleanup.cleanDeferred(receipts, local, mounted = { true })
            assertEquals(PendingCleanup.Summary(1, 0, 0), summary)
            assertFalse(part.exists())
            assertTrue(OperationArtifacts.pending(receipts).isEmpty())
            assertEquals(emptyList<String>(), key.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun killedBeforeThePartWasCreatedLeavesNothingToDelete() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            val other = File(key, "photo.jpg").apply { writeText("pixels") }
            val operation = OperationArtifacts(receipts).apply { begin() }
            createPart(operation, key, "film.mkv", "DC20-EA13", create = false)
            PendingCleanup.adoptInterrupted(receipts)
            assertEquals(1, PendingCleanup.cleanDeferred(receipts, local, mounted = { true }).deleted)
            assertTrue(OperationArtifacts.pending(receipts).isEmpty())
            assertEquals("pixels", other.readText()) // nothing else touched
        } finally { root.deleteRecursively() }
    }

    @Test fun folderCopyKilledKeepsCompleteFilesAndDeletesOnlyThePart() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            val operation = OperationArtifacts(receipts).apply { begin() }
            val folder = File(key, "Vacances").apply { mkdir() }
            operation.record(OperationArtifacts.Entry(folder.path, key.path, folder.name, true, folder.canonicalPath, true, false,
                "DC20-EA13", operation = operation.id, document = folder.canonicalPath))
            val done = File(folder, "a.jpg").apply { writeText("complet") } // verified and renamed before the kill
            val part = createPart(operation, folder, "b.jpg", "DC20-EA13").apply { writeText("moit") }
            PendingCleanup.adoptInterrupted(receipts)
            val summary = PendingCleanup.cleanDeferred(receipts, local, mounted = { true })
            assertEquals(0, summary.leftover)
            assertFalse(part.exists())
            assertEquals("complet", done.readText())
            assertTrue(folder.isDirectory)
            assertTrue(OperationArtifacts.pending(receipts).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun keyUnpluggedAtStartWaitsForItsReturn() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            val operation = OperationArtifacts(receipts).apply { begin() }
            val part = createPart(operation, key, "film.mkv", "DC20-EA13").apply { writeText("data") }
            PendingCleanup.adoptInterrupted(receipts)
            // Key absent: nothing deleted, the receipt stays deferred.
            assertEquals(PendingCleanup.Summary(0, 0, 0), PendingCleanup.cleanDeferred(receipts, local, mounted = { it == null }))
            assertTrue(part.exists())
            assertTrue(OperationArtifacts.pending(receipts).single().deferred)
            // Key plugged in again.
            assertEquals(1, PendingCleanup.cleanDeferred(receipts, local, mounted = { true }).deleted)
            assertFalse(part.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun internalTrashPartWithoutVolumeIsCleanedToo() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val trash = File(root, ".bonobo_trash").apply { mkdirs() }
            val operation = OperationArtifacts(receipts).apply { begin() }
            val part = createPart(operation, trash, "trash_x~0123456789abcdef_#_film.mkv", volume = null).apply { writeText("data") }
            PendingCleanup.adoptInterrupted(receipts)
            assertEquals(1, PendingCleanup.cleanDeferred(receipts, local, mounted = { it == null }).deleted)
            assertFalse(part.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun runningOrFinishedOperationsAndOldReceiptsAreNeverAdopted() {
        val root = directory()
        try {
            val receipts = File(root, "receipts")
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            // Running in this process.
            val running = OperationArtifacts(receipts)
            OperationArtifacts.markRunning(running.id)
            running.begin()
            val livePart = createPart(running, key, "a.mkv", "DC20-EA13")
            // Finished normally: its leftover is handed to the user (« Fichiers partiels à vérifier »), not deleted.
            val finished = OperationArtifacts(receipts).apply { begin() }
            val handed = createPart(finished, key, "b.mkv", "DC20-EA13")
            finished.end()
            // Receipt of an older version (no « live » flag).
            val legacy = OperationArtifacts(receipts)
            val old = createPart(legacy, key, "c.mkv", "DC20-EA13")
            try {
                assertTrue(OperationArtifacts.interrupted(receipts).isEmpty())
                assertEquals(0, PendingCleanup.adoptInterrupted(receipts))
                assertEquals(0, PendingCleanup.cleanDeferred(receipts, local, mounted = { true }).deleted)
                assertTrue(livePart.exists() && handed.exists() && old.exists())
            } finally { OperationArtifacts.markFinished(running.id) }
        } finally { root.deleteRecursively() }
    }
}
