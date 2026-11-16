package fr.bonobo.filemanager

import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.OperationArtifacts
import fr.bonobo.filemanager.util.SafFiles
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * Bug 14/11/2026: CLEANUP refused the app's own .bonobo-part (PARTIAL_LEFTOVER IDENTITY_MISMATCH),
 * after a cancel with the key present and after unplug + remount.
 */
class PartialCleanupIdentityTest {
    private val sent = 50_462_720L
    private val closedAt = 1_763_100_000_000L
    private val op = "abcd1234-0000-4000-8000-000000000000"
    private val partName = SafFiles.partName("film.mkv", op.take(8))

    /** Key with documents addressed by their document id (like externalstorage, stable across remount). */
    private class Key : ArtifactCleanup.Access {
        var mounted = true
        val nodes = mutableListOf<ArtifactCleanup.Node>()
        val deletes = mutableListOf<String>()
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        override fun list(parent: String) = if (!mounted) emptyList() else nodes.toList()
        override fun available(entry: OperationArtifacts.Entry) = mounted
        override fun matches(entry: OperationArtifacts.Entry, node: ArtifactCleanup.Node) =
            node.path == (entry.document ?: entry.path)
        override fun delete(entry: OperationArtifacts.Entry) { deletes += entry.path; nodes.removeAll { it.path == entry.path } }
        /** Fresh identity value on every listing, as with remounted FUSE inodes / mtime-based keys. */
        fun put(path: String, name: String, size: Long, modified: Long) {
            nodes += ArtifactCleanup.Node(path, name, false, "inode-${System.nanoTime()}", size, modified)
        }
    }

    private fun receipt(bytesSent: Long? = sent, modifiedAt: Long? = closedAt) = OperationArtifacts.Entry(
        "doc:$partName", "doc:root", partName, false,
        identity = "frozen-at-creation:0", owned = true, staging = true, volume = "DC20-EA13",
        operation = op, document = "doc:$partName", bytesSent = bytesSent, modifiedAt = modifiedAt)

    @Test fun cancelWithKeyPresentDeletesOwnPartialDespiteChangedIdentity() {
        val base = Files.createTempDirectory("identity-cancel-").toFile()
        try {
            val operation = OperationArtifacts(base, op)
            val entry = receipt()
            operation.record(entry)
            // Size = bytes sent, date within FAT 2 s granularity, identity value no longer the creation one.
            val key = Key().apply { put(entry.document!!, partName, sent, closedAt + 1_500) }
            val report = ArtifactCleanup.execute(operation, key)
            assertEquals(ArtifactCleanup.Outcome.DELETED, report.overall)
            assertEquals(listOf("doc:$partName"), key.deletes)
            assertTrue(OperationArtifacts.pending(base).isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun unplugThenRemountDeletesTruncatedPartial() {
        val base = Files.createTempDirectory("identity-remount-").toFile()
        try {
            val operation = OperationArtifacts(base, op)
            val entry = receipt()
            operation.record(entry)
            val key = Key().apply { mounted = false }
            // Unplugged: absence cannot be observed → UNKNOWN, receipt kept as deferred.
            val first = ArtifactCleanup.execute(operation, key)
            assertEquals(ArtifactCleanup.Outcome.UNKNOWN, first.overall)
            operation.record(entry.copy(deferred = true))
            // Remounted: the cache was never flushed, the file is SMALLER than the bytes sent.
            key.mounted = true
            key.put(entry.document!!, partName, 45_744_128L, closedAt - 60_000)
            val reopened = OperationArtifacts.open(base, op)
            assertTrue(reopened.entries().single().deferred)
            val second = ArtifactCleanup.execute(reopened, key, { it.deferred })
            assertEquals(ArtifactCleanup.Outcome.DELETED, second.overall)
            assertEquals(listOf("doc:$partName"), key.deletes)
        } finally { base.deleteRecursively() }
    }

    @Test fun reducedSizeIsAcceptedGrownOrLaterModifiedIsRefused() {
        val entry = receipt()
        fun node(size: Long, modified: Long) = ArtifactCleanup.Node("doc:$partName", partName, false, "x", size, modified)
        assertNull(ArtifactCleanup.mismatch(entry, node(45_744_128L, closedAt)))
        assertNull(ArtifactCleanup.mismatch(entry, node(0L, closedAt)))
        val grown = ArtifactCleanup.mismatch(entry, node(sent + 1, closedAt))!!
        assertEquals(TechnicalJournal.Result.SIZE_MISMATCH, grown.first)
        assertEquals(sent to sent + 1, grown.second) // expected vs found, for the journal
        val later = ArtifactCleanup.mismatch(entry, node(sent, closedAt + 60_000))!!
        assertEquals(TechnicalJournal.Result.MODIFIED_AFTER_INTERRUPTION, later.first)
        assertEquals(TechnicalJournal.Result.NAME_MISMATCH,
            ArtifactCleanup.mismatch(entry, ArtifactCleanup.Node("doc:$partName", "autre", false, "x", 1, closedAt))!!.first)
        // Interrupted before the streams closed (crash): nothing recorded, any size is accepted.
        assertNull(ArtifactCleanup.mismatch(receipt(null, null), node(sent * 2, closedAt * 2)))
    }

    @Test fun nameCollisionNeverTargetsTheOtherPartial() {
        val base = Files.createTempDirectory("identity-collision-").toFile()
        try {
            assertEquals(".film.mkv.abcd1234.bonobo-part", partName)
            val operation = OperationArtifacts(base, op)
            val entry = receipt()
            operation.record(entry)
            val key = Key().apply {
                put("doc:.film.mkv.bonobo-part", ".film.mkv.bonobo-part", 10L, closedAt) // older attempt
                put("doc:.film.mkv.ffff0000.bonobo-part", ".film.mkv.ffff0000.bonobo-part", 10L, closedAt) // other op
                put(entry.document!!, partName, sent, closedAt)
            }
            assertEquals(ArtifactCleanup.Outcome.DELETED, ArtifactCleanup.execute(operation, key).overall)
            assertEquals(listOf(entry.document), key.deletes) // only the document returned at creation
            assertEquals(2, key.nodes.size)
        } finally { base.deleteRecursively() }
    }

    @Test fun ownDocumentGoneAndForeignSameNameIsKept() {
        val base = Files.createTempDirectory("identity-foreign-").toFile()
        try {
            val operation = OperationArtifacts(base, op)
            val entry = receipt()
            operation.record(entry)
            val key = Key().apply { put("doc:someone-else", partName, 1L, closedAt) }
            assertEquals(ArtifactCleanup.Outcome.LEFTOVER, ArtifactCleanup.execute(operation, key).overall)
            assertTrue(key.deletes.isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun newReceiptFieldsSurviveRestart() {
        val base = Files.createTempDirectory("identity-persist-").toFile()
        try {
            OperationArtifacts(base, op).record(receipt())
            val stored = OperationArtifacts.open(base, op).entries().single()
            assertEquals(op, stored.operation); assertEquals("doc:$partName", stored.document)
            assertEquals(sent, stored.bytesSent); assertEquals(closedAt, stored.modifiedAt)
            OperationArtifacts.open(base, op).update(stored.path) { it.copy(bytesSent = 7) }
            assertEquals(7L, OperationArtifacts.open(base, op).entries().single().bytesSent)
        } finally { base.deleteRecursively() }
    }

    /** Retours 14/11 point 6: a cancelled folder copy holding verified files is KEPT_VERIFIED, not NOT_EMPTY. */
    @Test fun folderWithVerifiedFilesIsKeptForeignContentIsNot() {
        val base = Files.createTempDirectory("identity-kept-").toFile()
        try {
            val operation = OperationArtifacts(base, op)
            val folder = OperationArtifacts.Entry("doc:Photos", "doc:root", "Photos", true, identity = "doc:Photos",
                owned = true, staging = false, volume = "DC20-EA13", operation = op, document = "doc:Photos")
            operation.record(folder)
            val key = object : ArtifactCleanup.Access {
                val content = mutableMapOf("doc:root" to mutableListOf(ArtifactCleanup.Node("doc:Photos", "Photos", true, "doc:Photos")),
                    "doc:Photos" to mutableListOf(ArtifactCleanup.Node("doc:a.jpg", "a.jpg", false, "doc:a.jpg", 10, closedAt)))
                override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
                override fun list(parent: String) = content[parent]!!.toList()
                override fun available(entry: OperationArtifacts.Entry) = true
                override fun matches(entry: OperationArtifacts.Entry, node: ArtifactCleanup.Node) = node.path == entry.path
                override fun delete(entry: OperationArtifacts.Entry) = error("must not delete")
            }
            val foreign = ArtifactCleanup.execute(operation, key) // nothing says a.jpg was verified by us
            assertEquals(ArtifactCleanup.Outcome.LEFTOVER, foreign.overall)
            val kept = ArtifactCleanup.execute(operation, key, verified = { it.identity == "doc:a.jpg" })
            assertEquals(ArtifactCleanup.Outcome.KEPT, kept.outcomes.values.single())
            assertEquals(ArtifactCleanup.Outcome.DELETED, kept.overall) // clean: no partial left
            assertTrue(OperationArtifacts.pending(base).isEmpty())
        } finally { base.deleteRecursively() }
    }
}
