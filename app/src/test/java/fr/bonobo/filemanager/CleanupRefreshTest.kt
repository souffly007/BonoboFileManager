package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.OperationArtifacts
import fr.bonobo.filemanager.util.SafFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** After a cancellation, the displayed folder is refreshed as soon as the CLEANUP ends (DELETED / KEPT_VERIFIED). */
class CleanupRefreshTest {
    private val local = object : ArtifactCleanup.Access {
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        override fun list(parent: String) = (File(parent).listFiles() ?: throw IOException("absent")).map {
            ArtifactCleanup.Node(it.path, it.name, it.isDirectory, it.canonicalPath, if (it.isFile) it.length() else null, it.lastModified())
        }
        override fun matches(entry: OperationArtifacts.Entry, node: ArtifactCleanup.Node) =
            File(node.path).canonicalPath == File(entry.document ?: entry.path).canonicalPath
        override fun delete(entry: OperationArtifacts.Entry) = Files.delete(File(entry.path).toPath())
    }

    private fun part(operation: OperationArtifacts, parent: File): File {
        val file = File(parent, SafFiles.partName("film.mkv", operation.id.take(8))).apply { writeText("moit") }
        operation.record(OperationArtifacts.Entry(file.path, parent.path, file.name, false, file.canonicalPath, true, true,
            "DC20-EA13", operation = operation.id, document = file.canonicalPath))
        return file
    }

    @Test fun deletedPartAnnouncesItsFolderRightAfterCleanup() = runBlocking {
        val root = Files.createTempDirectory("refresh-").toFile()
        try {
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            val operation = OperationArtifacts(File(root, "receipts"))
            val part = part(operation, key)
            val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { ArtifactCleanup.cleaned.first() } }
            val report = ArtifactCleanup.execute(operation, local)
            assertEquals(ArtifactCleanup.Outcome.DELETED, report.overall)
            assertFalse(part.exists())
            val parents = received.await()
            assertTrue(ArtifactCleanup.concerns(key.path, parents))
            assertTrue(ArtifactCleanup.concerns(key.path + "/", parents))
            assertFalse(ArtifactCleanup.concerns(root.path, parents)) // another folder shown: no reload
            assertFalse(ArtifactCleanup.concerns(null, parents))
        } finally { root.deleteRecursively() }
    }

    @Test fun leftoverDoesNotAnnounceACleanFolder() = runBlocking {
        val root = Files.createTempDirectory("refresh-").toFile()
        try {
            val key = File(root, "DC20-EA13").apply { mkdirs() }
            val operation = OperationArtifacts(File(root, "receipts"))
            part(operation, key)
            val refusing = object : ArtifactCleanup.Access by local {
                override fun delete(entry: OperationArtifacts.Entry) = Unit // provider ignores the deletion
            }
            val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(300) { ArtifactCleanup.cleaned.first() } }
            assertEquals(ArtifactCleanup.Outcome.LEFTOVER, ArtifactCleanup.execute(operation, refusing).overall)
            assertNull(received.await())
        } finally { root.deleteRecursively() }
    }
}
