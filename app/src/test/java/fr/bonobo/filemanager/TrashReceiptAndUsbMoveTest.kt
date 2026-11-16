package fr.bonobo.filemanager

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Retours tests 14/11: receipts per trash entry (1), USB → corbeille through the verified circuit (2). */
class TrashReceiptAndUsbMoveTest {
    private fun directory() = Files.createTempDirectory("trash-receipt-").toFile()
    private suspend fun digest(path: String) = IntegrityVerifier.fingerprint(LocalIntegrityAccess, path)

    /** TrashRestorer's receipt logic with a local copy (receipt key = entry id). */
    private suspend fun restore(receipts: RestoreReceipts, entry: File, target: File, root: File) {
        val identity = FileIdentity.local(entry) { null to root }
        receipts.restore(TrashRestorer.receiptKey(entry), identity.encode(), { digest(entry.path) },
            { require(!target.exists()) { "La destination existe déjà" }; target.path },
            exists = { File(it).exists() },
            verify = { path, expected -> check(digest(path) == expected) },
            copy = { path -> Files.copy(entry.toPath(), File(path).toPath(), StandardCopyOption.COPY_ATTRIBUTES); path },
            compare = { stored -> TrashRestorer.compare(stored, identity) { entry.lastModified() } })
    }

    @Test fun entryNameCarriesUniqueIdAndStaysReadable() {
        val a = TrashPaths.entryName("/storage/emulated/0/Download/app.apk", "app.apk", "0123456789abcdef")
        val b = TrashPaths.entryName("/storage/emulated/0/Download/app.apk", "app.apk", "fedcba9876543210")
        assertNotEquals(a, b) // same file trashed twice: two names, two receipts
        assertEquals("0123456789abcdef", TrashPaths.entryId(a))
        assertEquals("/storage/emulated/0/Download/app.apk", TrashPaths.origin(a))
        assertEquals("app.apk", TrashPaths.displayName(a))
        // Older entries (no id) are still understood.
        val legacy = "trash_" + java.util.Base64.getUrlEncoder().encodeToString("/x/app.apk".toByteArray()) + "_#_app.apk"
        assertNull(TrashPaths.entryId(legacy)); assertEquals("/x/app.apk", TrashPaths.origin(legacy))
        assertTrue(TrashRestorer.receiptKey(File("/t", a)).startsWith("entry:"))
    }

    /** Bug 1: yesterday's restoration of the same APK (same name, same size) must not block today's entry. */
    @Test fun homonymousEntriesNeverShareAReceipt() = runBlocking {
        val root = directory()
        try {
            val trash = File(root, ".bonobo_trash").apply { mkdir() }
            val receipts = RestoreReceipts(File(root, "receipts"))
            val origin = File(root, "app.apk").path
            // Yesterday: entry 1, restored, then the restored copy was trashed again and entry 1 removed.
            val first = File(trash, TrashPaths.entryName(origin, "app.apk", "aaaaaaaaaaaaaaaa")).apply { writeText("APK") }
            first.setLastModified(1_763_000_000_000)
            val restored = File(root, "app.apk")
            restore(receipts, first, restored, root)
            first.delete(); restored.delete()
            // Today: same file, same name and size, other date → its own receipt, restored without prompt.
            val second = File(trash, TrashPaths.entryName(origin, "app.apk", "bbbbbbbbbbbbbbbb")).apply { writeText("APK") }
            second.setLastModified(1_763_100_000_000)
            restore(receipts, second, restored, root)
            assertEquals("APK", restored.readText())
        } finally { root.deleteRecursively() }
    }

    /** Bug 1, entries written by the previous version (key = path): a receipt whose copy is gone is stale. */
    @Test fun legacyHomonymousReceiptIsStaleWhenItsCopyIsGone() = runBlocking {
        val root = directory()
        try {
            val trash = File(root, ".bonobo_trash").apply { mkdir() }
            val receipts = RestoreReceipts(File(root, "receipts"))
            val name = "trash_" + java.util.Base64.getUrlEncoder().encodeToString(File(root, "app.apk").path.toByteArray()) + "_#_app.apk"
            val entry = File(trash, name).apply { writeText("APK"); setLastModified(1_763_000_000_000) }
            val restored = File(root, "app.apk")
            restore(receipts, entry, restored, root)
            restored.delete()
            entry.setLastModified(1_763_100_000_000) // the new homonymous entry: another date
            restore(receipts, entry, restored, root) // no MODIFIED_MISMATCH prompt
            assertTrue(restored.exists())
            // But while the previous copy still exists, a different entry is still reported.
            val other = File(root, "other.apk")
            entry.setLastModified(1_763_200_000_000)
            val error = runCatching { restore(receipts, entry, other, root) }.exceptionOrNull()
            assertTrue("$error", error is RestoreMismatchException)
            assertFalse(other.exists())
        } finally { root.deleteRecursively() }
    }

    /** Verified local circuit, as SafFiles.move does it: .bonobo-part, SHA-256, rename, then delete the source. */
    private class Circuit {
        val steps = mutableListOf<String>()
        var corruptCopy = false
        suspend fun move(source: String, target: String) {
            val from = File(source); val to = File(target)
            val part = File(to.parentFile, SafFiles.partName(to.name, "abcd1234"))
            steps += "part"
            val evidence = VerifiedStreamCopy.write({ from.inputStream() }, { part.outputStream() }, from.length())
            if (corruptCopy) part.appendText("!")
            try { VerifiedStreamCopy.verify({ part.inputStream() }, evidence, part.length()); steps += "sha" }
            catch (e: Exception) { part.delete(); throw e }
            Files.move(part.toPath(), to.toPath()); steps += "rename"
            check(from.delete()); steps += "delete-source"
        }
    }

    private fun mover(trash: File, internal: File, usb: File, circuit: Circuit, space: (File) -> Unit = {}) = TrashMover(trash,
        volumeRoot = { p -> if (p.startsWith(usb.canonicalPath)) usb else if (p.startsWith(internal.canonicalPath)) internal else null },
        storageOf = { p -> if (p.startsWith(usb.path)) TechnicalJournal.Storage.USB else TechnicalJournal.Storage.LOCAL },
        requireSpace = { circuit.steps += "space"; space(it) },
        verifiedMove = circuit::move)

    /** Bug 2: a USB file goes to the trash through SPACE_CHECK → part → SHA → rename → delete source; storage=USB. */
    @Test fun usbFileToTrashUsesVerifiedCircuitAndUsbLabel() = runBlocking {
        val root = directory()
        val logs = File(root, "logs")
        val journal = TechnicalJournal(logs).apply { setEnabled(true) }
        Diagnostic.journal = journal
        try {
            val internal = File(root, "emulated0").apply { mkdirs() }
            val usb = File(root, "DC20-EA13").apply { mkdirs() }
            val trash = File(internal, ".bonobo_trash")
            val source = File(usb, "film.mkv").apply { writeText("film".repeat(1000)) }
            val circuit = Circuit()
            val entry = mover(trash, internal, usb, circuit).move(source.path)
            assertEquals(listOf("space", "part", "sha", "rename", "delete-source"), circuit.steps)
            assertFalse(source.exists())
            assertEquals("film".repeat(1000), entry.readText())
            assertNotNull(TrashPaths.entryId(entry.name))
            assertTrue(trash.listFiles()!!.none { it.name.endsWith(OperationArtifacts.PART_SUFFIX) })
            journal.awaitIdle()
            val log = File(logs, "current.log").readText()
            assertTrue(log, log.lines().any { it.contains("DELETE_END OK") && it.contains("storage=USB") && it.contains("mode=TRASH") })
            assertFalse(log, log.lines().any { it.contains("mode=TRASH") && it.contains("storage=LOCAL") })
        } finally { Diagnostic.journal = null; journal.setEnabled(false); root.deleteRecursively() }
    }

    @Test fun failedVerificationOrMissingSpaceKeepsTheUsbSource() = runBlocking {
        val root = directory()
        try {
            val internal = File(root, "emulated0").apply { mkdirs() }
            val usb = File(root, "DC20-EA13").apply { mkdirs() }
            val trash = File(internal, ".bonobo_trash")
            val source = File(usb, "photo.jpg").apply { writeText("pixels") }
            // SHA mismatch: source kept, no entry, no partial.
            val corrupt = Circuit().apply { corruptCopy = true }
            assertTrue(runCatching { mover(trash, internal, usb, corrupt).move(source.path) }.isFailure)
            assertTrue(source.exists()); assertEquals(emptyList<String>(), trash.list()!!.toList())
            assertFalse("delete-source" in corrupt.steps)
            // Internal storage full: refused before writing anything.
            val full = Circuit()
            val error = runCatching {
                mover(trash, internal, usb, full) { throw TransferFailure(FailureKind.INSUFFICIENT_SPACE, "plein") }.move(source.path)
            }.exceptionOrNull()
            assertEquals(FailureKind.INSUFFICIENT_SPACE, (error as TransferFailure).kind)
            assertEquals(listOf("space"), full.steps)
            assertTrue(source.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun sameVolumeIsARenameWithoutCopy() = runBlocking {
        val root = directory()
        try {
            val internal = File(root, "emulated0").apply { mkdirs() }
            val usb = File(root, "DC20-EA13").apply { mkdirs() }
            val source = File(internal, "notes.txt").apply { writeText("notes") }
            val circuit = Circuit()
            val entry = mover(File(internal, ".bonobo_trash"), internal, usb, circuit).move(source.path)
            assertTrue(circuit.steps.isEmpty())
            assertEquals("notes", entry.readText()); assertFalse(source.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun replaceWithoutRoomExplainsWhy() {
        val message = SpaceCheck.message(usb = true, needed = 2L * 1024 * 1024 * 1024, available = 500L * 1024 * 1024, replacing = true)
        assertTrue(message, message.contains("doit être entièrement copiée"))
        assertTrue(message, message.contains("avant que l’ancienne soit supprimée"))
        assertTrue(SpaceCheck.enough(100, 1, 100 + SpaceCheck.margin(100, 1)))
        assertFalse(SpaceCheck.enough(100, 1, 100 + SpaceCheck.margin(100, 1) - 1))
    }
}
