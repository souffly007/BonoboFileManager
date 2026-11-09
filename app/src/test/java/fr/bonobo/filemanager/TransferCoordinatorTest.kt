package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TransferCoordinatorTest {
    private fun source(name: String) = TransferSource(StorageRef.Local("/isolated/$name"), name, false)
    private val destination = StorageRef.Local("/isolated/destination")
    private fun request(vararg names: String) = TransferRequest(names.map(::source), destination, TransferOperation.MOVE)
    private open class Engine : TransferEngine {
        val copied = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override suspend fun inspect(source: TransferSource) = Inventory(1, 100)
        override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
            copied += source.name
            TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, source.ref.path, source.name, fileBytes = 0, fileTotal = 100))
            repeat(100) { TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 1, fileBytes = it + 1L)) }
            TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            currentCoroutineContext().ensureActive()
            TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
            currentCoroutineContext().ensureActive()
            deleted += source.name
        }
    }
    @Test fun sequentialBatchKeepsExactThrottledCountersAndDestinationSnapshot() = runTest {
        val engine = Engine()
        val coordinator = TransferCoordinator(this, clock = { 0 })
        coordinator.submit(request("a", "b"), engine)!!.join()
        val state = coordinator.state.value
        assertEquals(TransferPhase.DONE, state.phase)
        assertEquals(200L, state.bytes); assertEquals(200L, state.totalBytes)
        assertEquals(2L, state.completedFiles); assertEquals(2L, state.totalFiles)
        assertEquals(destination.path, state.destination)
        assertEquals(listOf("a", "b"), engine.deleted)
        assertEquals(state, coordinator.result(state.id))
    }
    @Test fun failureOfOneElementDoesNotDeleteItOrPreventOtherElements() = runTest {
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                if (source.name == "bad") { copied += source.name; error("Destination tronquée : source conservée") }
                super.execute(source, destination, operation)
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("first", "bad", "last"), engine)!!.join()
        assertEquals(listOf("first", "last"), engine.deleted)
        assertEquals(listOf(ItemStatus.DONE, ItemStatus.FAILED, ItemStatus.DONE), coordinator.state.value.results.map { it.status })
        assertEquals(TransferPhase.ERROR, coordinator.state.value.phase)
        assertEquals(2L, coordinator.state.value.completedFiles)
    }
    @Test fun secondJobIsRejectedWhileFirstIsActive() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) { gate.await(); super.execute(source, destination, operation) }
        }
        val coordinator = TransferCoordinator(this)
        val first = coordinator.submit(request("first"), engine)!!
        runCurrent()
        assertNull(coordinator.submit(request("second"), engine))
        gate.complete(Unit); first.join()
        assertNotNull(coordinator.submit(request("second"), engine))
        advanceUntilIdle()
    }
    @Test fun cancelWaitsForBlockedMotorAndPreservesUnstartedSources() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                currentCoroutineContext().ensureActive()
                super.execute(source, destination, operation)
            }
        }
        val coordinator = TransferCoordinator(this)
        val job = coordinator.submit(request("blocked", "next"), engine)!!
        entered.await(); coordinator.cancel(); runCurrent()
        assertEquals(TransferPhase.CANCEL_REQUESTED, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.active); assertFalse(job.isCompleted)
        assertNull(coordinator.submit(request("other"), engine))
        release.complete(Unit); job.join()
        assertFalse(coordinator.state.value.active)
        assertEquals(TransferPhase.CANCELLED, coordinator.state.value.phase)
        assertEquals(listOf(ItemStatus.UNCERTAIN, ItemStatus.CANCELLED), coordinator.state.value.results.map { it.status })
        assertTrue(engine.deleted.isEmpty())
    }
    @Test fun cancelDuringVerificationDoesNotStartDeleteAndKeepsFinishedResults() = runTest {
        val entered = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                if (source.name == "verify") {
                    copied += source.name
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                    entered.complete(Unit); awaitCancellation()
                }
                super.execute(source, destination, operation)
            }
        }
        val coordinator = TransferCoordinator(this)
        val job = coordinator.submit(request("finished", "verify", "queued"), engine)!!
        entered.await(); coordinator.cancel(); job.join()
        assertEquals(listOf("finished"), engine.deleted)
        assertEquals(listOf(ItemStatus.DONE, ItemStatus.UNCERTAIN, ItemStatus.CANCELLED), coordinator.state.value.results.map { it.status })
        assertEquals(1L, coordinator.state.value.completedFiles)
    }
    @Test fun cancelBeforeScheduledStartNeverLeavesBusyState() = runTest {
        val engine = Engine(); val coordinator = TransferCoordinator(this)
        val job = coordinator.submit(request("a"), engine)!!
        coordinator.cancel(); job.join()
        assertFalse(coordinator.state.value.active); assertTrue(engine.copied.isEmpty())
        assertEquals(TransferPhase.CANCELLED, coordinator.state.value.phase)
    }
    @Test fun fullyCopiedBytesNeverAnnounceCompletionBeforeVerification() = runTest {
        val verify = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 100, fileBytes = 100))
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                entered.complete(Unit); verify.await()
                currentCoroutineContext().ensureActive(); deleted += source.name
            }
        }
        val coordinator = TransferCoordinator(this)
        val job = coordinator.submit(request("a"), engine)!!
        entered.await()
        assertEquals(1f, coordinator.state.value.copyFraction)
        assertEquals(TransferPhase.VERIFYING, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.active); assertEquals(0L, coordinator.state.value.completedFiles)
        assertTrue(engine.deleted.isEmpty())
        verify.complete(Unit); job.join()
        assertEquals(TransferPhase.DONE, coordinator.state.value.phase)
    }
    @Test fun deletionFailureIsAnErrorAndOtherElementsRemainIndependent() = runTest {
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                if (source.name == "delete-fails") {
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
                    TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                    error("Suppression non confirmée ; copie conservée")
                }
                super.execute(source, destination, operation)
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("delete-fails", "ok"), engine)!!.join()
        assertEquals(listOf("ok"), engine.deleted)
        assertEquals(TransferPhase.ERROR, coordinator.state.value.phase)
        assertEquals(ItemStatus.FAILED, coordinator.state.value.results.first().status)
    }
    @Test fun coveredSelectionIsNotTransferredTwiceAndOnlyFinalResultsClearClipboard() = runTest {
        val engine = object : Engine() {
            override suspend fun normalize(sources: List<TransferSource>) = sources.take(1)
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("parent", "covered-child"), engine)!!.join()
        assertEquals(listOf("parent"), engine.copied)
        assertEquals(2, coordinator.state.value.results.size)
        assertTrue(coordinator.state.value.results.all { it.status == ItemStatus.DONE })
        assertEquals(1L, coordinator.state.value.completedFiles)
    }
    @Test fun persistenceCleanupStillExcludesTheNextJob() = runTest {
        lateinit var coordinator: TransferCoordinator
        val engine = Engine()
        var attempted = false
        coordinator = TransferCoordinator(this, persist = { state ->
            if (!state.active && state.phase == TransferPhase.DONE) {
                attempted = true
                assertNull(coordinator.submit(request("next"), engine))
            }
        })
        coordinator.submit(request("first"), engine)!!.join()
        assertTrue(attempted); assertEquals(listOf("first"), engine.copied)
    }

    @Test fun unknownSizeNeverProducesFalsePercentage() = runTest {
        val engine = object : Engine() { override suspend fun inspect(source: TransferSource) = Inventory(1, null) }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("unknown"), engine)!!.join()
        assertNull(coordinator.state.value.copyFraction); assertNull(coordinator.state.value.totalBytes)
        assertEquals(100L, coordinator.state.value.bytes)
    }
    @Test fun changedTreeInvalidatesPreflightTotalsAndCountsActualFiles() = runTest {
        val engine = object : Engine() {
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                repeat(2) { TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = 10, fileStart = true)) }
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("changed-tree"), engine)!!.join()
        assertEquals(2L, coordinator.state.value.completedFiles)
        assertNull(coordinator.state.value.totalFiles); assertNull(coordinator.state.value.totalBytes)
        assertEquals(20L, coordinator.state.value.bytes)
    }

    @Test fun zeroByteFileIsSuccessfulWithoutDivisionByZero() = runTest {
        val engine = object : Engine() {
            override suspend fun inspect(source: TransferSource) = Inventory(1, 0)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, fileBytes = 0, fileTotal = 0))
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("empty"), engine)!!.join()
        assertEquals(TransferPhase.DONE, coordinator.state.value.phase); assertNull(coordinator.state.value.copyFraction)
    }
    @Test fun countsBeyondFourGiBRemainLongAndVerificationAddsNoBytes() = runTest {
        val size = 5L * 1024 * 1024 * 1024
        val engine = object : Engine() {
            override suspend fun inspect(source: TransferSource) = Inventory(1, size)
            override suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation) {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = size, fileBytes = size))
                TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("large"), engine)!!.join()
        assertEquals(size, coordinator.state.value.bytes); assertEquals(1f, coordinator.state.value.copyFraction)
        assertNull(coordinator.state.value.speed); assertNull(coordinator.state.value.etaSeconds)
    }
    @Test fun inventoryOverflowStopsBeforeAnyCopy() = runTest {
        val engine = object : Engine() { override suspend fun inspect(source: TransferSource) = Inventory(1, Long.MAX_VALUE) }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("a", "b"), engine)!!.join()
        assertEquals(TransferPhase.ERROR, coordinator.state.value.phase); assertTrue(engine.copied.isEmpty())
    }
    @Test fun preflightFailureIsPerElementAndOtherItemsStillRun() = runTest {
        val engine = object : Engine() {
            override suspend fun inspect(source: TransferSource): Inventory {
                if (source.name == "permission") error("Permission SAF révoquée")
                return super.inspect(source)
            }
        }
        val coordinator = TransferCoordinator(this)
        coordinator.submit(request("permission", "ok"), engine)!!.join()
        assertEquals(listOf("ok"), engine.deleted)
        assertNull(coordinator.state.value.totalBytes)
        assertEquals(ItemStatus.FAILED, coordinator.state.value.results.first().status)
    }
    @Test fun unreadableJournalBlocksReplayAndActiveJournalBecomesInterrupted() {
        val dir = Files.createTempDirectory("bonobo-journal-test").toFile()
        try {
            val file = java.io.File(dir, "latest.properties"); val journal = TransferJournal(file)
            journal.write(TransferState(id = "isolated", active = true, phase = TransferPhase.VERIFYING,
                currentName = "private-name", currentSource = "secret-password", destination = "secret-host"))
            val restored = journal.read()
            assertFalse(restored.active); assertEquals(TransferPhase.INTERRUPTED, restored.phase)
            assertFalse(file.readText().contains("secret")); assertFalse(file.readText().contains("private-name"))
            file.writeText("broken")
            assertEquals(TransferPhase.INTERRUPTED, journal.read().phase)
        } finally { dir.deleteRecursively() }
    }
    @Test fun journalWriteFailureBeforeStartPreservesAllSources() = runTest {
        val engine = Engine(); val coordinator = TransferCoordinator(this, persist = { error("disk failure") })
        coordinator.submit(request("a"), engine)!!.join()
        assertTrue(engine.copied.isEmpty()); assertEquals(TransferPhase.ERROR, coordinator.state.value.phase)
    }
    @Test fun speedAndEtaRequireMonotoneStableMeasurementsAndResetBetweenPhases() {
        var time = 0L; val rate = TransferRate { time }
        assertEquals(null to null, rate.sample(0, 5000))
        time = 500; assertNull(rate.sample(500, 4500).first)
        time = 1000; assertEquals(1000L, rate.sample(1000, 4000).first)
        time = 2000; assertNull(rate.sample(2000, 3000).second)
        time = 3000; assertEquals(2L, rate.sample(3000, 2000).second)
        time = 4000; assertNull(rate.sample(3010, 1990).second)
        rate.reset(); assertEquals(null to null, rate.sample(3010, 1990))
    }
}
