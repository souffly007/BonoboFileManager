package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.transfer.TransferManager
import fr.bonobo.filemanager.domain.transfer.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** USB → corbeille shown as a transfer: correct plural in the result, « Réessayer » waits for the key. */
class TrashTransferTest {
    private val trash = StorageRef.Local("/storage/emulated/0/.bonobo_trash")
    private fun source(name: String) = TransferSource(StorageRef.Local("/storage/DC20-EA13/$name"), name, false)
    private val failing = object : TransferEngine {
        override suspend fun inspect(source: TransferSource) = Inventory(1, 10)
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            throw IllegalStateException("Élément introuvable : actualisez.")
        }
    }

    private suspend fun notice(vararg names: String): String {
        var result = ""
        kotlinx.coroutines.coroutineScope {
            val coordinator = TransferCoordinator(this)
            coordinator.submit(TransferRequest(names.map(::source), trash, TransferOperation.TRASH), failing)!!.join()
            result = coordinator.state.value.notice!!
        }
        return result
    }

    @Test fun trashFailureNoticeHasCorrectPlural() = runTest {
        assertEquals("1 élément non mis à la corbeille : touchez « Détails ».", notice("a.jpg"))
        assertEquals("2 éléments non mis à la corbeille : touchez « Détails ».", notice("a.jpg", "b.jpg"))
        assertFalse(notice("a.jpg", "b.jpg").contains("(s)"))
    }

    @Test fun retryWaitsForEveryUsbKeyOfTheOperation() {
        val mounted = mutableSetOf<String>()
        // Source key of a mise à la corbeille unplugged: blocked.
        assertNotNull(TransferManager.blockReason(setOf("DC20-EA13")) { it in mounted })
        mounted += "DC20-EA13"
        assertNull(TransferManager.blockReason(setOf("DC20-EA13")) { it in mounted })
        // Internal storage only: never blocked.
        assertNull(TransferManager.blockReason(emptySet()) { false })
        // Key → other key: both must be present.
        assertNotNull(TransferManager.blockReason(setOf("DC20-EA13", "1234-ABCD")) { it in mounted })
    }
}
