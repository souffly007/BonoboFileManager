package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransferInventoryTest {
    private fun access(children: (String) -> List<IntegrityVerifier.Child>?) = object : IntegrityVerifier.Access {
        override fun children(path: String) = children(path)
        override fun open(path: String): java.io.InputStream = error("L'inspection ne doit pas lire les contenus")
    }
    @Test fun inventoryCountsFilesNotFoldersAndPreservesUnknownSizes() = runBlocking {
        val tree = access { path -> when (path) {
            "root" -> listOf(IntegrityVerifier.Child("folder", "folder"), IntegrityVerifier.Child("file", "file"))
            "folder" -> listOf(IntegrityVerifier.Child("unknown", "unknown"))
            else -> null
        } }
        val stats = TransferInventory.inspect(tree, "root") { if (it == "file") 5L * 1024 * 1024 * 1024 else null }
        assertEquals(2L, stats.files); assertNull(stats.bytes)
    }
    @Test fun repeatedIdentityAndProviderRefusalStopBeforeFollowingUnsafeNodes() = runBlocking {
        val cycle = access { listOf(IntegrityVerifier.Child("cycle", "root")) }
        assertTrue(runCatching { TransferInventory.inspect(cycle, "root") { 0 } }.isFailure)
        val refused = access { error("Point de réanalyse SMB refusé") }
        assertTrue(runCatching { TransferInventory.inspect(refused, "root") { 0 } }.isFailure)
    }
    @Test fun deepTreeIsRefusedWithoutRecursiveStackOverflow() = runBlocking {
        var inspected = 0
        val tree = access { path -> inspected++; listOf(IntegrityVerifier.Child("child", (path.toInt() + 1).toString())) }
        val result = runCatching { TransferInventory.inspect(tree, "0") { 0 } }
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(TransferInventory.MAX_DEPTH + 1, inspected)
    }
}
