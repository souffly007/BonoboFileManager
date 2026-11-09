package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.presentation.ui.main.MainUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Beta19Test {
    @Test fun acknowledgedResultSurvivesRepeatedReloadsAndRetainsHistory() = runTest {
        val directory = Files.createTempDirectory("beta19-journal").toFile()
        try {
            val journal = TransferJournal(directory.resolve("latest.properties"))
            val completed = TransferState(id = "completed", phase = TransferPhase.DONE, completedFiles = 3)
            journal.write(completed)
            val coordinator = TransferCoordinator(this, journal::write, journal.read())
            coordinator.dismiss()
            repeat(5) {
                val restored = journal.read()
                assertTrue(restored.acknowledged)
                assertFalse(restored.active)
                assertEquals("completed", restored.id)
                assertEquals(3L, restored.completedFiles)
                TransferCoordinator(this, journal::write, restored).dismiss()
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedActiveTransferStillRequiresAttention() {
        val directory = Files.createTempDirectory("beta19-interrupted").toFile()
        try {
            val journal = TransferJournal(directory.resolve("latest.properties"))
            journal.write(TransferState(id = "running", active = true, acknowledged = true))
            assertFalse(journal.read().acknowledged)
            assertEquals(TransferPhase.INTERRUPTED, journal.read().phase)
        } finally { directory.deleteRecursively() }
    }
    @Test fun panelSwitchKeepsUsbAndLocalLocationsAndSelectionsInBothOrders() {
        for ((left, right) in listOf("content://test/tree/usb" to "/test/internal", "/test/internal" to "content://test/tree/usb")) {
            val initial = MainUiState(currentPath = left, secondPath = right, isDualPane = true,
                selectedPaths = setOf("$left/file"), inactiveSelectedPaths = setOf("$right/file"))
            val switched = initial.activate(2)
            assertEquals(left, switched.currentPath)
            assertEquals(right, switched.secondPath)
            assertEquals(setOf("$right/file"), switched.selectedPaths)
            assertEquals(initial, switched.activate(1))
            assertEquals(switched, switched.activate(2))
        }
    }
    @Test fun failedAcknowledgementKeepsResultVisibleForRetry() = runTest {
        val coordinator = TransferCoordinator(this, persist = { error("unavailable") },
            initial = TransferState(id = "completed"))
        coordinator.dismiss()
        assertFalse(coordinator.state.value.acknowledged)
        assertTrue(coordinator.state.value.notice!!.contains("Acquittement non enregistré"))
    }
    @Test fun groupedRestoreContinuesAfterConflictAndPreventsDuplicateCopies() = runTest {
        val directory = Files.createTempDirectory("beta19-batch").toFile()
        try {
            val sources = listOf("first", "conflict", "last").map { directory.resolve(it).apply { writeText("source-$it") } }
            val destination = directory.resolve("chosen").apply { mkdir() }
            destination.resolve("conflict").writeText("existing")
            val receipts = fr.bonobo.filemanager.util.RestoreReceipts(directory.resolve("receipts"))
            suspend fun restore(source: java.io.File): Result<Unit> = runCatching {
                val target = destination.resolve(source.name)
                receipts.restore(source.path, "entry-${source.name}",
                    { fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(fr.bonobo.filemanager.util.LocalIntegrityAccess, source.path) },
                    { require(!target.exists()); target.path }, { java.io.File(it).exists() },
                    { path, digest -> check(fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(fr.bonobo.filemanager.util.LocalIntegrityAccess, path) == digest) },
                    { source.copyTo(java.io.File(it)); it })
            }
            val outcomes = mutableListOf<Boolean>()
            fr.bonobo.filemanager.util.BatchTransfer.run(sources, { _, _ -> }, ::restore,
                { _, result -> outcomes += result.isSuccess })
            assertEquals(listOf(true, false, true), outcomes)
            assertEquals("existing", destination.resolve("conflict").readText())
            assertTrue(sources.all { it.exists() })
            assertTrue(restore(sources.first()).exceptionOrNull() is fr.bonobo.filemanager.util.AlreadyRestoredException)
            assertEquals(3, destination.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

}
