package fr.bonobo.filemanager

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.DeletionTrace
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.OperationArtifacts
import fr.bonobo.filemanager.util.SafMutations
import fr.bonobo.filemanager.util.VerifiedStreamCopy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

class Beta195ReliabilityTest {
    private class FakeAccess : ArtifactCleanup.Access {
        val children = mutableMapOf<String, MutableList<ArtifactCleanup.Node>>("root" to mutableListOf())
        var refuse = false
        val deleted = mutableListOf<String>()
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = !entry.path.startsWith("saf-")
        override fun list(parent: String) = children[parent]?.toList() ?: throw IOException("unavailable")
        override fun delete(entry: OperationArtifacts.Entry) {
            if (refuse) throw SecurityException("refused")
            children[entry.parent]!!.removeAll { it.path == entry.path }
            deleted += entry.path
        }
        fun add(entry: OperationArtifacts.Entry) {
            children.getOrPut(entry.parent) { mutableListOf() } +=
                ArtifactCleanup.Node(entry.path, entry.name, entry.directory, entry.identity)
            if (entry.directory) children[entry.path] = mutableListOf()
        }
    }
    private fun entry(path: String, parent: String, directory: Boolean = false, owned: Boolean = true) =
        OperationArtifacts.Entry(path, parent, path, directory, "identity-$path", owned, false)

    @Test fun cancellationWaitsForOutputCloseThenCleansOnlyOwnedFile() = runBlocking {
        val base = Files.createTempDirectory("beta195-cancel-").toFile()
        try {
            val receipts = OperationArtifacts(base)
            val access = FakeAccess()
            val created = entry("created", "root")
            val previous = entry("previous", "root", owned = false)
            receipts.record(created); receipts.record(previous)
            access.add(created); access.add(previous)
            var closed = false
            var job: Job? = null
            job = launch {
                try {
                    VerifiedStreamCopy.write({ ByteArrayInputStream(ByteArray(256 * 1024)) },
                        { object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } } },
                        256 * 1024L, progress = { _, _ -> job?.cancel() })
                } finally {
                    assertTrue(closed)
                    val residues = ArtifactCleanup.run(receipts, access)
                    assertEquals(listOf(previous), residues)
                }
            }
            job.join()
            assertEquals(listOf("created"), access.deleted)
            assertTrue(access.children["root"]!!.any { it.path == "previous" })
        } finally { base.deleteRecursively() }
    }

    @Test fun refusedCleanupLeavesReceiptAndRetryConflictsUntilManualResolution() {
        val base = Files.createTempDirectory("beta195-retry-").toFile()
        try {
            val receipts = OperationArtifacts(base)
            val access = FakeAccess().apply { refuse = true }
            val folder = entry("saf-folder", "root", directory = true)
            val partial = entry("partial", "saf-folder")
            receipts.record(folder); receipts.record(partial)
            access.add(folder); access.add(partial)
            assertEquals(2, ArtifactCleanup.run(receipts, access).size)
            assertEquals(2, OperationArtifacts.pending(base).size)
            val provider = object : SafMutations.Access {
                var creations = 0
                override fun identity(path: String) = path
                override fun list(parent: String) = access.list(parent).map { SafMutations.Node(it.path, it.name, it.directory) }
                override fun create(parent: String, name: String, directory: Boolean, mime: String): String {
                    creations++
                    access.add(entry(name, parent, directory))
                    return name
                }
                override fun delete(path: String) = false
                override fun rename(path: String, name: String): String? = null
            }
            val conflict = runCatching { SafMutations(provider).create("root", "saf-folder", true, "directory") }.exceptionOrNull()!!
            assertTrue(conflict.message!!.contains("Choisissez un autre nom"))
            assertEquals(0, provider.creations)
            assertEquals("new-name", SafMutations(provider).create("root", "new-name", false, "file"))
            assertEquals(1, provider.creations)
            assertTrue(access.deleted.isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun deletionEventsAndNavigationVocabularyContainOnlyClosedCategories() {
        val base = Files.createTempDirectory("beta195-diagnostic-").toFile()
        val journal = TechnicalJournal(base)
        Diagnostic.journal = journal
        try {
            journal.setEnabled(true)
            val provider = object : SafMutations.Access {
                override fun identity(path: String) = path
                override fun list(parent: String) = listOf(SafMutations.Node("opaque-id", "sentinel-private-name", true))
                override fun create(parent: String, name: String, directory: Boolean, mime: String) = error("unused")
                override fun delete(path: String) = true // Acknowledged without removing the node.
                override fun rename(path: String, name: String): String? = null
            }
            val trace = DeletionTrace(TechnicalJournal.Storage.SAF, TechnicalJournal.Kind.DIRECTORY,
                TechnicalJournal.Mode.PERMANENT)
            trace.start()
            val deletion = SafMutations(provider).delete("opaque-id", "root", trace)
            assertEquals(SafMutations.Deletion.INDETERMINATE, deletion.state)
            trace.end(TechnicalJournal.Result.INDETERMINATE)
            Diagnostic.detail(TechnicalJournal.Event.NAV_PROVIDER_FAILURE, TechnicalJournal.Result.NOT_FOUND,
                storage = TechnicalJournal.Storage.SAF, phase = TechnicalJournal.Phase.READ)
            journal.awaitIdle()
            val bytes = ByteArrayOutputStream()
            assertTrue(journal.export(bytes))
            val text = bytes.toString()
            assertTrue(text.contains("DELETE_PROVIDER_RESULT OK"))
            assertTrue(text.contains("DELETE_VERIFY INDETERMINATE"))
            assertTrue(text.contains("DELETE_END INDETERMINATE"))
            assertTrue(text.contains("storage=SAF kind=DIRECTORY mode=PERMANENT"))
            assertFalse(text.contains("content://"))
            assertFalse(text.contains("sentinel-private-name"))
            assertFalse(text.contains("private-secret"))
        } finally { Diagnostic.journal = null; base.deleteRecursively() }
    }
}
