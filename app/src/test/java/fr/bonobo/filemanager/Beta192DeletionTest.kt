package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.DeletionBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Beta192DeletionTest {
    @Test fun successWaitsForEngineAndFailureWithoutMessageIsNotSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pending = async { DeletionBatch.run(listOf("file", "folder"), false) {
            gate.await()
            if (it == "file") Result.success(Unit) else Result.failure(Exception())
        } }
        testScheduler.runCurrent()
        assertFalse(pending.isCompleted)
        gate.complete(Unit)
        val result = pending.await()
        assertEquals(listOf("file"), result.completed)
        assertTrue(result.message.contains("1/2"))
        assertTrue(result.message.contains("Suppression non confirmée"))
    }
    @Test fun localFilesAndFoldersAreRemovedOnlyAfterActualResult() = runTest {
        val base = Files.createTempDirectory("beta192-delete").toFile()
        try {
            val file = base.resolve("file").apply { writeText("fixture") }
            val folder = base.resolve("folder").apply { mkdir(); resolve("child").writeText("fixture") }
            val result = DeletionBatch.run(listOf(file.path, folder.path), false) {
                runCatching { check(java.io.File(it).deleteRecursively()); Unit }
            }
            assertEquals(2, result.completed.size)
            assertFalse(file.exists()); assertFalse(folder.exists())
        } finally { base.deleteRecursively() }
    }
    @Test fun safTrashAndMixedSelectionNeverInvokeAnyDeletion() = runTest {
        for (paths in listOf(listOf("content://opaque/file"), listOf("/local/folder", "content://opaque/folder"))) {
            val result = DeletionBatch.run(paths, true) { error("Must not delete") }
            assertTrue(result.completed.isEmpty())
            assertTrue(result.message.contains("Aucune suppression"))
        }
    }
    @Test fun safPermanentResultsIncludeRefusalAndThrownError() = runTest {
        val paths = listOf("content://opaque/file", "content://opaque/folder", "content://opaque/error")
        val result = DeletionBatch.run(paths, false) {
            when (it) {
                paths[0] -> Result.success(Unit)
                paths[1] -> Result.failure(SecurityException("Lecture seule"))
                else -> throw java.io.IOException("Fournisseur indisponible")
            }
        }
        assertEquals(listOf(paths[0]), result.completed)
        assertTrue(result.message.contains("Lecture seule"))
        assertTrue(result.message.contains("Fournisseur indisponible"))
    }
    @Test fun localTrashReportsOnlyConfirmedMoves() = runTest {
        val base = Files.createTempDirectory("beta192-trash").toFile()
        try {
            val trash = base.resolve("trash").apply { mkdir() }
            val file = base.resolve("file").apply { writeText("fixture") }
            val folder = base.resolve("folder").apply { mkdir() }
            val result = DeletionBatch.run(listOf(file.path, folder.path), true) {
                runCatching { Files.move(java.io.File(it).toPath(), trash.resolve(java.io.File(it).name).toPath()); Unit }
            }
            assertEquals(2, result.completed.size)
            assertTrue(trash.resolve("file").exists()); assertTrue(trash.resolve("folder").isDirectory)
        } finally { base.deleteRecursively() }
    }
}
