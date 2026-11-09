package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Beta192TransferTest {
    private val source = TransferSource(StorageRef.Local("/fixture/source"), "source", false)
    private val request = TransferRequest(listOf(source), StorageRef.SafDocument("content://opaque/destination"), TransferOperation.MOVE)
    @Test fun copiedBytesWithDeletionFailureNeverBecomeMoveSuccess() = runTest {
        val coordinator = TransferCoordinator(this)
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 4)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 4))
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                throw java.io.IOException("Copie effectuée, source conservée ou partiellement supprimée : suppression source non confirmée.")
            }
        }
        coordinator.submit(request, engine)!!.join()
        val result = coordinator.state.value
        assertEquals(4L, result.bytes)
        assertEquals(0L, result.completedFiles)
        assertEquals(TransferPhase.ERROR, result.phase)
        assertEquals(ItemStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().message!!.contains("source conservée"))
    }
    @Test fun cancelledCleanupDiagnosticSurvivesAndNextOperationStartsAtZero() = runTest {
        val coordinator = TransferCoordinator(this)
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 4)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 3))
                throw CancellationException("Annulation : une destination partielle peut subsister ; source conservée.")
            }
        }
        coordinator.submit(request, engine)!!.join()
        val first = coordinator.state.value
        assertEquals(TransferPhase.CANCELLED, first.phase)
        assertTrue(first.results.single().message!!.contains("destination partielle"))
        val next = object : TransferEngine {
            override suspend fun inspect(source: TransferSource): Inventory {
                assertEquals(0L, coordinator.state.value.bytes)
                assertNotEquals(first.id, coordinator.state.value.id)
                return Inventory(1, 4)
            }
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {}
        }
        coordinator.submit(request, next)!!.join()
    }
}
