package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.presentation.ui.dashboard.HomeFolderShortcut
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Beta191Test {
    @Test fun downloadsShortcutResolvesConcreteDestinationAndLeavesOtherTilesAndSafBookmarksAlone() {
        val download = "/isolated/internal/Download"
        assertEquals(download, HomeFolderShortcut.downloadsPath("Téléchargements", null, download))
        for (title in listOf("Images", "Vidéos", "Musique", "Documents", "Gros fichiers", "Corbeille", "Favoris", "Explorateur")) {
            assertNull(HomeFolderShortcut.downloadsPath(title, null, download))
        }
        assertNull(HomeFolderShortcut.downloadsPath("Téléchargements", "content://test/tree/chosen", download))
        assertNull(HomeFolderShortcut.downloadsPath("Téléchargements", "/isolated/another", download))
    }

    @Test fun newCopyAndMoveAfterCancellationResetEveryCounterAndUseNewIdentity() = runTest {
        for (operation in listOf(TransferOperation.COPY, TransferOperation.MOVE)) {
            val source = TransferSource(StorageRef.Local("/isolated/source"), "source", false)
            val request = TransferRequest(listOf(source), StorageRef.Local("/isolated/Download"), operation)
            val coordinator = TransferCoordinator(this, clock = { 0 })
            val entered = CompletableDeferred<Unit>()
            val oldEngine = object : TransferEngine {
                override suspend fun inspect(source: TransferSource) = Inventory(1, 100)
                override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, fileBytes = 0, fileTotal = 100, fileStart = true))
                    // Buffered chunk must be flushed on cancellation, then never reused by the next job.
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 40, fileBytes = 40))
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val oldJob = coordinator.submit(request, oldEngine)!!
            entered.await()
            val oldId = coordinator.state.value.id
            coordinator.cancel(); oldJob.join()
            val cancelled = coordinator.state.value
            assertEquals(TransferPhase.CANCELLED, cancelled.phase)
            assertEquals(40L, cancelled.bytes)
            assertFalse(cancelled.active)
            coordinator.dismiss()
            assertTrue(coordinator.state.value.acknowledged)

            val release = CompletableDeferred<Unit>()
            val nextEngine = object : TransferEngine {
                override suspend fun inspect(source: TransferSource) = Inventory(1, 100)
                override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, fileBytes = 0, fileTotal = 100, fileStart = true))
                    release.await()
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 7, fileBytes = 7))
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                }
            }
            val nextJob = coordinator.submit(request, nextEngine)!!
            val fresh = coordinator.state.value
            assertNotEquals(oldId, fresh.id)
            assertEquals(TransferPhase.PREPARING, fresh.phase)
            assertEquals(operation, fresh.operation)
            assertTrue(fresh.active)
            assertFalse(fresh.cancelRequested); assertFalse(fresh.acknowledged)
            assertEquals(0L, fresh.bytes); assertEquals(0L, fresh.fileBytes)
            assertEquals(0L, fresh.completedFiles); assertEquals(0, fresh.processedRoots)
            assertNull(fresh.totalBytes); assertNull(fresh.totalFiles); assertNull(fresh.fileTotal)
            assertNull(fresh.speed); assertNull(fresh.etaSeconds); assertNull(fresh.copyFraction)
            assertNull(fresh.notice); assertTrue(fresh.results.isEmpty())
            runCurrent()
            assertEquals(0f, coordinator.state.value.copyFraction)
            release.complete(Unit); nextJob.join()
            assertEquals(7L, coordinator.state.value.bytes)
            assertEquals(7L, coordinator.state.value.fileBytes)
            assertEquals(TransferPhase.DONE, coordinator.state.value.phase)
            assertEquals(cancelled, coordinator.result(oldId))
        }
    }
}
