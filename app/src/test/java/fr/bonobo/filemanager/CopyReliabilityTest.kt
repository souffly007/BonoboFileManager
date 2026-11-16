package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.CopyFailures
import fr.bonobo.filemanager.util.OperationArtifacts
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** Session 13/11/2026: atomic write, verified cleanup, conflicts, full key. */
@OptIn(ExperimentalCoroutinesApi::class)
class CopyReliabilityTest {
    // ── Cleanup: DELETED / LEFTOVER / UNKNOWN ──
    private class Volume : ArtifactCleanup.Access {
        var mounted = true
        val folders = mutableMapOf<String, MutableList<ArtifactCleanup.Node>>("root" to mutableListOf())
        val deletes = mutableListOf<String>()
        var refuse = false
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        // An unmounted mount point lists as EMPTY: this must not be taken as a deletion.
        override fun list(parent: String): List<ArtifactCleanup.Node> = if (!mounted) emptyList()
            else folders[parent]?.toList() ?: throw IOException("unavailable")
        override fun available(entry: OperationArtifacts.Entry) = mounted && folders.containsKey(entry.parent)
        override fun delete(entry: OperationArtifacts.Entry) {
            if (refuse) throw IOException("refused")
            deletes += entry.path
            folders[entry.parent]!!.removeAll { it.path == entry.path }
        }
        fun add(entry: OperationArtifacts.Entry) {
            folders.getOrPut(entry.parent) { mutableListOf() } += ArtifactCleanup.Node(entry.path, entry.name, entry.directory, entry.identity)
            if (entry.directory) folders[entry.path] = mutableListOf()
        }
    }
    private fun part(name: String = ".film.mkv.bonobo-part", parent: String = "root", owned: Boolean = true) =
        OperationArtifacts.Entry(name, parent, name, false, "id-$name", owned, true, "DC20-EA13")

    @Test fun removedKeyIsUnknownNeverDeleted() {
        val base = Files.createTempDirectory("reliability-removed-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val volume = Volume()
            val entry = part()
            operation.record(entry); volume.add(entry)
            volume.mounted = false
            val report = ArtifactCleanup.execute(operation, volume)
            assertEquals(ArtifactCleanup.Outcome.UNKNOWN, report.overall)
            assertTrue(volume.deletes.isEmpty())
            assertEquals(listOf(entry), OperationArtifacts.pending(base)) // kept for reconnection
        } finally { base.deleteRecursively() }
    }

    @Test fun deletionIsReportedOnlyAfterObservedAbsence() {
        val base = Files.createTempDirectory("reliability-deleted-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val volume = Volume()
            val folder = OperationArtifacts.Entry("folder", "root", "folder", true, "id-folder", true, false, "DC20-EA13")
            val entry = part(parent = "folder")
            for (e in listOf(folder, entry)) { operation.record(e); volume.add(e) }
            val report = ArtifactCleanup.execute(operation, volume)
            assertEquals(ArtifactCleanup.Outcome.DELETED, report.overall)
            assertEquals(listOf(".film.mkv.bonobo-part", "folder"), volume.deletes) // SAF folders are deletable once empty
            assertTrue(OperationArtifacts.pending(base).isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun refusedDeletionIsLeftover() {
        val base = Files.createTempDirectory("reliability-leftover-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val volume = Volume().apply { refuse = true }
            val entry = part()
            operation.record(entry); volume.add(entry)
            assertEquals(ArtifactCleanup.Outcome.LEFTOVER, ArtifactCleanup.execute(operation, volume).overall)
        } finally { base.deleteRecursively() }
    }

    @Test fun unconfirmedPartIsCleanedByReservedNameOnly() {
        val base = Files.createTempDirectory("reliability-unowned-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val volume = Volume()
            val reserved = part(owned = false)
            val foreign = OperationArtifacts.Entry("photo.jpg", "root", "photo.jpg", false, "id-x", false, true)
            for (e in listOf(reserved, foreign)) { operation.record(e); volume.add(e) }
            val report = ArtifactCleanup.execute(operation, volume)
            assertEquals(ArtifactCleanup.Outcome.DELETED, report.outcomes[reserved])
            assertEquals(ArtifactCleanup.Outcome.LEFTOVER, report.outcomes[foreign])
            assertEquals(listOf(reserved.path), volume.deletes)
        } finally { base.deleteRecursively() }
    }

    @Test fun deferredFlagAndVolumeSurviveRestart() {
        val base = Files.createTempDirectory("reliability-deferred-").toFile()
        try {
            val operation = OperationArtifacts(base)
            operation.record(part().copy(deferred = true))
            val reopened = OperationArtifacts.open(base, operation.id).entries().single()
            assertTrue(reopened.deferred); assertEquals("DC20-EA13", reopened.volume)
            OperationArtifacts.markRunning(operation.id)
            assertTrue(OperationArtifacts.finishedIds(base).isEmpty()) // never touched while running
            OperationArtifacts.markFinished(operation.id)
            assertEquals(listOf(operation.id), OperationArtifacts.finishedIds(base))
        } finally { base.deleteRecursively() }
    }

    // ── Failure classification ──
    @Test fun noSpaceIsFoundThroughTheCauseChain() {
        val wrapped = IOException("Support inaccessible", IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(CopyFailures.isNoSpace(wrapped))
        assertFalse(CopyFailures.isNoSpace(IOException("write failed: EIO (I/O error)")))
        assertTrue(CopyFailures.isFileTooLarge(IOException("write failed: EFBIG (File too large)")))
    }

    // ── Coordinator ──
    private val destination = StorageRef.Local("/isolated/usb")
    private fun source(name: String, directory: Boolean = false) = TransferSource(StorageRef.Local("/isolated/$name"), name, directory)

    @Test fun fullKeyStopsTheBatchWithAFrenchMessage() = runTest {
        val executed = mutableListOf<String>()
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 2L * 1024 * 1024 * 1024)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                executed += source.name
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 1_500L * 1024 * 1024))
                throw TransferFailure(FailureKind.DISK_FULL, "Plus d’espace libre pendant l’écriture.", "ENOSPC", usb = true)
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(TransferRequest(listOf(source("a"), source("b")), destination, TransferOperation.COPY), engine)!!.join()
        val state = coordinator.state.value
        assertEquals(listOf("a"), executed) // pointless to continue on a full key
        assertEquals(TransferPhase.ERROR, state.phase)
        assertEquals(FailureKind.DISK_FULL, state.failure); assertTrue(state.failureOnUsb)
        assertTrue(state.notice!!, state.notice!!.startsWith("Clé USB pleine : 1,5 Go copiés sur 4,0 Go."))
        assertEquals(listOf(ItemStatus.FAILED, ItemStatus.NOT_STARTED), state.results.map { it.status })
        assertEquals("ENOSPC", state.results.first().detail)
    }

    @Test fun preflightRefusalWritesNothing() = runTest {
        var executed = false
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 10)
            override suspend fun preflight(request: TransferRequest, inventories: Map<StorageRef, Inventory>) {
                throw TransferFailure(FailureKind.INSUFFICIENT_SPACE, "Espace insuffisant sur la clé USB", usb = true)
            }
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) { executed = true }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(TransferRequest(listOf(source("a")), destination, TransferOperation.COPY), engine)!!.join()
        assertFalse(executed)
        assertEquals(FailureKind.INSUFFICIENT_SPACE, coordinator.state.value.failure)
        assertFalse(coordinator.state.value.active)
    }

    @Test fun skippedElementIsNotAnError() = runTest {
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 10)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                if (source.name == "same") throw TransferSkipped("Identique au fichier déjà présent : ignoré.")
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(TransferRequest(listOf(source("same"), source("new")), destination, TransferOperation.COPY), engine)!!.join()
        val state = coordinator.state.value
        assertEquals(TransferPhase.DONE, state.phase)
        assertEquals(listOf(ItemStatus.SKIPPED, ItemStatus.DONE), state.results.map { it.status })
        assertTrue(state.notice!!.contains("1 élément ignoré"))
    }

    @Test fun verifiedCleanCancellationIsCancelledNotUncertain() = runTest {
        val engine = object : TransferEngine {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 10)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                throw CleanCancellation("1 fichier partiel supprimé.")
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(TransferRequest(listOf(source("big")), destination, TransferOperation.COPY), engine)!!.join()
        val state = coordinator.state.value
        assertEquals(TransferPhase.CANCELLED, state.phase)
        assertEquals(ItemStatus.CANCELLED, state.results.single().status)
        assertTrue(state.notice!!.startsWith("Copie annulée."))
    }

    @Test fun pluralAgreement() {
        assertEquals("1 erreur", TransferText.plural(1, "erreur"))
        assertEquals("2 erreurs", TransferText.plural(2, "erreur"))
        assertEquals("0 erreur", TransferText.plural(0, "erreur"))
    }
}
