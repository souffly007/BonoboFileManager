package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.*
import fr.bonobo.filemanager.domain.transfer.*
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Beta193SafReliabilityTest {
    private class Provider : SafMutations.Access {
        val nodes = mutableListOf(SafMutations.Node("existing", "existing", false))
        var deleteMode = "ok"
        var failList = false
        var createMode = "ok"
        var renameMode = "ok"
        var deleted = 0
        var created = 0
        override fun identity(path: String) = path
        override fun list(parent: String): List<SafMutations.Node> {
            if (failList) throw SecurityException("permission revoked")
            return nodes.toList()
        }
        override fun create(parent: String, name: String, directory: Boolean, mime: String): String {
            created++
            if (createMode == "refused") throw IOException("publication refused")
            if (createMode == "existing") return "existing"
            val node = SafMutations.Node("id-$created", name, directory)
            nodes += node
            if (createMode == "throw-after-create") throw IOException("created but no returned identity")
            if (createMode == "query-fails") failList = true
            return node.path
        }
        override fun delete(path: String): Boolean {
            deleted++
            return when (deleteMode) {
                "refused" -> false
                "permission" -> throw SecurityException("revoked")
                "no-effect" -> true
                "query-fails" -> { nodes.removeAll { it.path == path }; failList = true; true }
                "partial-folder" -> { nodes.removeAll { it.path == "child" }; throw IOException("partially deleted") }
                else -> { nodes.removeAll { it.path == path }; true }
            }
        }
        override fun rename(path: String, name: String): String? {
            if (renameMode == "refused") return null
            if (renameMode == "no-effect") return path
            val old = nodes.single { it.path == path }
            nodes.remove(old)
            nodes += old.copy(path = "new-id", name = name)
            if (renameMode == "query-fails") failList = true
            return "new-id"
        }
    }

    @Test fun creationConflictDoesNotOpenOrMutateExistingDocument() {
        val provider = Provider()
        assertTrue(runCatching { SafMutations(provider).create("root", "existing", false, "x") }.isFailure)
        assertEquals(0, provider.created)
        assertEquals(0, provider.deleted)
        provider.createMode = "existing"
        var recorded = false
        assertTrue(runCatching { SafMutations(provider).create("root", "new", false, "x") { recorded = true } }.isFailure)
        assertFalse(recorded)
        assertEquals("existing", provider.nodes.single().path)
    }

    @Test fun actualCreationIsRecordedEvenIfConfirmationLosesPermission() {
        val provider = Provider().apply { createMode = "query-fails" }
        val recorded = mutableListOf<SafMutations.Node>()
        assertTrue(runCatching { SafMutations(provider).create("root", "new", false, "x", record = recorded::add) }.isFailure)
        assertEquals("id-1", recorded.single().path)
        assertEquals(0, provider.deleted)
    }

    @Test fun deletionRequiresObservedAbsenceAndAccessibleParent() {
        for ((mode, expected) in listOf("ok" to SafMutations.Deletion.CONFIRMED,
            "refused" to SafMutations.Deletion.REFUSED, "permission" to SafMutations.Deletion.REFUSED,
            "no-effect" to SafMutations.Deletion.INDETERMINATE, "query-fails" to SafMutations.Deletion.INDETERMINATE)) {
            val provider = Provider().apply { deleteMode = mode }
            assertEquals(mode, expected, SafMutations(provider).delete("existing", "root").state)
        }
        val provider = Provider().apply { failList = true }
        assertEquals(SafMutations.Deletion.INDETERMINATE, SafMutations(provider).delete("existing", "root").state)
        assertEquals(0, provider.deleted)
    }

    @Test fun partialFolderDeletionNeverReportsConfirmed() {
        val provider = Provider().apply {
            nodes += SafMutations.Node("folder", "folder", true)
            nodes += SafMutations.Node("child", "child", false)
            deleteMode = "partial-folder"
        }
        assertEquals(SafMutations.Deletion.INDETERMINATE, SafMutations(provider).delete("folder", "root").state)
        assertTrue(provider.nodes.any { it.path == "folder" })
        assertFalse(provider.nodes.any { it.path == "child" })
    }

    @Test fun renameUsesAndConfirmsChangedUri() {
        val provider = Provider()
        var reference = "existing"
        assertEquals("new-id", SafMutations(provider).rename(reference, "root", "renamed") { reference = it })
        assertEquals("new-id", reference)
        assertEquals("renamed", provider.nodes.single().name)
    }

    @Test fun renameRefusalAndLyingProviderCannotBecomeSuccess() {
        for (mode in listOf("refused", "no-effect", "query-fails")) {
            val provider = Provider().apply { renameMode = mode }
            var reference = "existing"
            assertTrue(runCatching { SafMutations(provider).rename(reference, "root", "renamed") { reference = it } }.isFailure)
            if (mode == "query-fails") assertEquals("new-id", reference)
            assertEquals(0, provider.deleted)
        }
    }

    @Test fun bothStreamsCloseAndCloseErrorsRemainObservable() = runBlocking {
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { inputClosed = true; throw IOException("input-close") }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; throw IOException("output-close") }
        }
        val failure = runCatching { VerifiedStreamCopy.write({ input }, { output }, 3) }.exceptionOrNull()!!
        assertEquals("output-close", failure.message)
        assertEquals("input-close", failure.suppressed.single().message)
        assertTrue(inputClosed); assertTrue(outputClosed)
    }

    @Test fun partialWriteKeepsPrimaryAndSuppressedClosureErrors() = runBlocking {
        var inputClosed = false
        var accepted = 0L
        val input = object : ByteArrayInputStream(ByteArray(128 * 1024)) {
            override fun close() { inputClosed = true }
        }
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Use bounded blocks")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (accepted > 0) throw IOException("partial-write")
                accepted += length
            }
            override fun close() { throw IOException("close-failed") }
        }
        val failure = runCatching { VerifiedStreamCopy.write({ input }, { output }, 128 * 1024L) }.exceptionOrNull()!!
        assertEquals("partial-write", failure.message)
        assertEquals("close-failed", failure.suppressed.single().message)
        assertEquals(64 * 1024L, accepted)
        assertTrue(inputClosed)
    }

    @Test fun outputOpenFlushAndSyncFailuresCloseResources() = runBlocking {
        for (mode in listOf("open", "flush", "sync")) {
            var inputClosed = false
            var outputClosed = false
            val input = object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { inputClosed = true } }
            val output = object : ByteArrayOutputStream() {
                override fun flush() { if (mode == "flush") throw IOException("flush") }
                override fun close() { outputClosed = true }
            }
            assertTrue(runCatching { VerifiedStreamCopy.write({ input }, {
                if (mode == "open") throw IOException("open") else output
            }, 1, sync = { if (mode == "sync") throw IOException("sync") }) }.isFailure)
            assertTrue(inputClosed)
            assertEquals(mode != "open", outputClosed)
        }
    }

    @Test fun exactBytesMetadataAndShaAreAllRequired() = runBlocking {
        val output = ByteArrayOutputStream()
        val evidence = VerifiedStreamCopy.write({ ByteArrayInputStream("original".toByteArray()) }, { output }, 8)
        VerifiedStreamCopy.verify({ ByteArrayInputStream(output.toByteArray()) }, evidence, 8)
        for (content in listOf("", "short", "modified")) {
            assertTrue(runCatching { VerifiedStreamCopy.verify({ ByteArrayInputStream(content.toByteArray()) }, evidence, 8) }.isFailure)
        }
        assertTrue(runCatching { VerifiedStreamCopy.verify({ ByteArrayInputStream(output.toByteArray()) }, evidence, 7) }.isFailure)
        assertTrue(runCatching { VerifiedStreamCopy.write({ ByteArrayInputStream(byteArrayOf(1)) }, { ByteArrayOutputStream() }, 2) }.isFailure)
    }

    @Test fun syntheticStreamCrossesTwoGiBWithoutWholeFileAllocation() = runBlocking {
        val size = 2_147_483_649L
        fun generated() = object : InputStream() {
            var remaining = size
            override fun read(): Int = error("Use bounded blocks")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 17.toByte())
                remaining -= count
                return count
            }
        }
        var accepted = 0L
        var largestBlock = 0
        val sinkDigest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = error("Use bounded blocks")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                accepted += length
                largestBlock = maxOf(largestBlock, length)
                sinkDigest.update(buffer, offset, length)
            }
        }
        val evidence = VerifiedStreamCopy.write(::generated, { sink }, size)
        assertEquals(size, evidence.bytes); assertEquals(size, accepted)
        assertTrue(largestBlock <= 64 * 1024)
        assertArrayEquals(sinkDigest.digest(), evidence.sha256)
        // This is a regenerated synthetic destination, not a physical USB persistence test.
        VerifiedStreamCopy.verify(::generated, evidence, size)
    }

    @Test fun materializedSyntheticCopyClosesThenReopensForVerification() = runBlocking {
        val base = Files.createTempDirectory("beta193-stream-").toFile()
        try {
            val source = File(base, "source")
            val target = File(base, "target")
            source.outputStream().use { out -> repeat(49) { out.write(ByteArray(64 * 1024) { 19 }) } }
            val evidence = VerifiedStreamCopy.write({ source.inputStream() }, { target.outputStream() }, source.length())
            VerifiedStreamCopy.verify({ target.inputStream() }, evidence, target.length())
            assertEquals(source.length(), target.length())
        } finally { base.deleteRecursively() }
    }

    @Test fun publicationOrderAndRefusalAreExplicit() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals("published", ControlledPublication.run(
            { calls += "stage-verified"; "temporary" },
            { assertEquals("temporary", it); calls += "publish"; "published" },
            { assertEquals("published", it); calls += "published-verified" }))
        assertEquals(listOf("stage-verified", "publish", "published-verified"), calls)
        var verified = false
        assertTrue(runCatching { ControlledPublication.run({ "temp" }, { throw IOException("refused") }, { verified = true }) }.isFailure)
        assertFalse(verified)
    }

    @Test fun cancelledStageNeverPublishesAndFailedVerificationNeverDeletesSource() = runBlocking {
        var published = false
        assertTrue(runCatching { ControlledPublication.run({ throw CancellationException("stage") },
            { published = true; "out" }, {}) }.exceptionOrNull() is CancellationException)
        assertFalse(published)
        var deleted = false
        val failure = runCatching { VerifiedMove.finish("target", false,
            { throw IOException("corrupt") }, { deleted = true }) }.exceptionOrNull()!!
        assertTrue(failure.message!!.contains("déplacement incomplet")); assertFalse(deleted)
    }

    @Test fun usbMoveRetainsSourceAndDeletionFailureRetainsCopy() = runBlocking {
        for (retain in listOf(true, false)) {
            var destination = true
            var source = true
            var deletes = 0
            val failure = runCatching { VerifiedMove.finish("target", retain, { check(destination) }, {
                deletes++; throw IOException("delete refused")
            }) }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains(if (retain)
                "Copie vérifiée — source conservée par sécurité. Déplacement non terminé."
                else "Copie effectuée, déplacement incomplet"))
            assertTrue(source); assertTrue(destination)
            assertEquals(if (retain) 0 else 1, deletes)
        }
    }

    private class CleanupAccess : ArtifactCleanup.Access {
        val folders = mutableMapOf<String, MutableList<ArtifactCleanup.Node>>("root" to mutableListOf())
        val deletes = mutableListOf<String>()
        var refuse = false
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        override fun list(parent: String): List<ArtifactCleanup.Node> = folders[parent]?.toList() ?: throw IOException("unavailable")
        override fun delete(entry: OperationArtifacts.Entry) {
            if (refuse) throw IOException("refused")
            deletes += entry.path
            folders[entry.parent]!!.removeAll { it.identity == entry.identity }
        }
        fun add(entry: OperationArtifacts.Entry) {
            folders.getOrPut(entry.parent) { mutableListOf() } += ArtifactCleanup.Node(entry.path, entry.name, entry.directory, entry.identity)
            if (entry.directory) folders[entry.path] = mutableListOf()
        }
    }
    private fun receipt(path: String, parent: String = "root", directory: Boolean = false, owned: Boolean = true) =
        OperationArtifacts.Entry(path, parent, path, directory, "identity-$path", owned, true)

    @Test fun cleanupCannotDeletePreexistingReplacementOrForeignChildren() {
        val base = Files.createTempDirectory("beta193-receipts-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val access = CleanupAccess()
            val replaced = receipt("replaced")
            operation.record(replaced); access.add(replaced.copy(identity = "foreign"))
            val uncertain = receipt("uncertain", owned = false)
            operation.record(uncertain); access.add(uncertain)
            val folder = receipt("folder", directory = true)
            operation.record(folder); access.add(folder)
            access.add(receipt("foreign-child", "folder"))
            val residues = ArtifactCleanup.run(operation, access)
            assertEquals(3, residues.size); assertTrue(access.deletes.isEmpty())
            assertEquals(3, OperationArtifacts.pending(base).size)
        } finally { base.deleteRecursively() }
    }

    @Test fun cancelledCleanupFailureSurvivesRestartAndRequiresManualResolution() {
        val base = Files.createTempDirectory("beta193-cancel-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val entry = receipt("partial")
            val access = CleanupAccess().apply { add(entry); refuse = true }
            operation.record(entry)
            assertEquals(listOf(entry), ArtifactCleanup.run(operation, access))
            assertEquals(listOf(entry), OperationArtifacts.pending(base))
            OperationArtifacts.acknowledge(base, entry)
            assertTrue(OperationArtifacts.pending(base).isEmpty())
            assertTrue(access.deletes.isEmpty()) // acknowledgement never deletes a document
        } finally { base.deleteRecursively() }
    }

    @Test fun successfulCleanupDeletesOnlyTrackedChildrenThenEmptyParent() {
        val base = Files.createTempDirectory("beta193-clean-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val access = CleanupAccess()
            for (entry in listOf(receipt("folder", directory = true), receipt("child", "folder"))) {
                operation.record(entry); access.add(entry)
            }
            assertTrue(ArtifactCleanup.run(operation, access).isEmpty())
            assertEquals(listOf("child", "folder"), access.deletes)
            assertTrue(OperationArtifacts.pending(base).isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun realCoroutineCancellationClosesStreamsAndRetainsRefusedCleanupReceipt() = runBlocking {
        val base = Files.createTempDirectory("beta193-live-cancel-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val entry = receipt("partial")
            val access = CleanupAccess().apply { add(entry); refuse = true }
            operation.record(entry)
            var inputClosed = false
            var outputClosed = false
            var residual = false
            val work = launch {
                val activeJob = currentCoroutineContext().job
                try {
                    VerifiedStreamCopy.write({ object : ByteArrayInputStream(ByteArray(256 * 1024)) {
                        override fun close() { inputClosed = true }
                    } }, { object : ByteArrayOutputStream() {
                        override fun close() { outputClosed = true }
                    } }, 256 * 1024L, progress = { _, _ -> activeJob.cancel() })
                    fail("Cancelled copy must not complete")
                } catch (e: CancellationException) {
                    residual = withContext(NonCancellable) { ArtifactCleanup.run(operation, access).isNotEmpty() }
                    throw e
                }
            }
            work.join()
            assertTrue(work.isCancelled); assertTrue(inputClosed); assertTrue(outputClosed)
            assertTrue(residual); assertEquals(listOf(entry), OperationArtifacts.pending(base))
        } finally { base.deleteRecursively() }
    }

    @Test fun genericSafDirectoryCleanupIsRefusedEvenWhenCurrentlyEmpty() {
        val base = Files.createTempDirectory("beta193-empty-folder-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val folder = receipt("folder", directory = true)
            val provider = CleanupAccess().apply { add(folder) }
            operation.record(folder)
            val conservative = object : ArtifactCleanup.Access by provider {
                override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = false
            }
            assertEquals(listOf(folder), ArtifactCleanup.run(operation, conservative))
            assertTrue(provider.deletes.isEmpty())
        } finally { base.deleteRecursively() }
    }

    @Test fun creationThatThrowsAfterMutationKeepsUnconfirmedIntentWithoutDeletingByName() {
        val base = Files.createTempDirectory("beta193-unknown-create-").toFile()
        try {
            val operation = OperationArtifacts(base)
            val unknown = receipt("requested-location", owned = false)
            val provider = Provider().apply { createMode = "throw-after-create" }
            var returnedIdentity = false
            assertTrue(runCatching { SafMutations(provider).create("root", "new", false, "x",
                beforeCreate = { operation.record(unknown) }, record = { returnedIdentity = true }) }.isFailure)
            assertFalse(returnedIdentity)
            assertTrue(provider.nodes.any { it.name == "new" })
            val cleanup = CleanupAccess()
            assertEquals(listOf(unknown), ArtifactCleanup.run(operation, cleanup))
            assertTrue(cleanup.deletes.isEmpty())
            assertEquals(listOf(unknown), OperationArtifacts.pending(base))
        } finally { base.deleteRecursively() }
    }
}
