package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class Beta17Test {
    @Test fun legacyOriginAndInvalidPaths() {
        val origin = "/storage/ABCD-1234/dossier é/fichier.txt"
        val encoded = Base64.getUrlEncoder().encodeToString(origin.toByteArray())
        assertEquals(origin, TrashPaths.origin("trash_${encoded}_#_fichier.txt"))
        listOf("trash_123_old.txt", "unknown", "trash_???_#_x").forEach {
            assertTrue(runCatching { TrashPaths.origin(it) }.isFailure)
        }
    }
    @Test fun restorationNeverOverwritesOrTargetsTrash() {
        val root = Files.createTempDirectory("trash-test").toFile()
        try {
            val trash = File(root, "trash").apply { mkdir() }
            val existing = File(root, "existing").apply { writeText("intact") }
            assertTrue(runCatching { TrashPaths.destination(existing.path, trash) }.isFailure)
            assertEquals("intact", existing.readText())
            assertTrue(runCatching { TrashPaths.destination(File(trash, "nested/file").path, trash) }.isFailure)
            assertTrue(runCatching { TrashPaths.destination("relative", trash) }.isFailure)
            assertEquals(File(root, "new"), TrashPaths.destination(File(root, "new").path, trash))
        } finally { root.deleteRecursively() }
    }
    @Test fun batchContinuesAfterFailureAndReportsEachItem() = runBlocking {
        val results = mutableListOf<Pair<Int, Boolean>>()
        val progress = mutableListOf<Int>()
        BatchTransfer.run(listOf(1, 2, 3), { index, _ -> progress += index },
            { if (it == 2) throw IllegalStateException("conflict") else Result.success(Unit) },
            { item, result -> results += item to result.isSuccess })
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(listOf(1 to true, 2 to false, 3 to true), results)
    }
    @Test fun cancellationStopsBeforeNextSource() = runBlocking {
        val done = mutableListOf<Int>()
        val job = launch {
            BatchTransfer.run(listOf(1, 2, 3), { _, _ -> },
                { if (it == 2) throw CancellationException("cancel") else Result.success(Unit) },
                { item, _ -> done += item })
        }
        job.join()
        assertEquals(listOf(1), done)
        assertTrue(job.isCancelled)
    }
}
