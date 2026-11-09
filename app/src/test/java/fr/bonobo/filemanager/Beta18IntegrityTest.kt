package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Beta18IntegrityTest {
    private fun directory() = Files.createTempDirectory("bonobo-lot1-").toFile()
    private suspend fun digest(file: File) = IntegrityVerifier.fingerprint(LocalIntegrityAccess, file.path)
    private suspend fun restore(root: File, source: File, target: File, copy: suspend (String) -> String = {
        source.copyTo(File(it)); it
    }) = RestoreReceipts(File(root, "receipts")).restore(source.path, "entry-1", { digest(source) },
        { require(!target.exists()) { "conflict" }; target.path }, { File(it).exists() },
        { path, expected -> check(digest(File(path)) == expected) }, copy)

    @Test fun receiptSurvivesRestartAndDifferentDestinationDoesNotDuplicate() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "restored")
            restore(root, source, target)
            val second = File(root, "second")
            assertTrue(runCatching { restore(root, source, second) }.exceptionOrNull() is AlreadyRestoredException)
            assertFalse(second.exists()); assertEquals("original", source.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentRestorationsCreateOneCopy() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val jobs = (1..2).map { index -> async { runCatching {
                restore(root, source, File(root, "restored-$index")) { delay(20); source.copyTo(File(it)); it }
            } } }
            val results = jobs.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is AlreadyRestoredException })
            assertEquals(1, root.listFiles()!!.count { it.name.startsWith("restored-") })
        } finally { root.deleteRecursively() }
    }
    @Test fun incompleteInterruptedRestoreBlocksAnotherDestination() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("complete source") }
            val target = File(root, "partial")
            assertTrue(runCatching { restore(root, source, target) { File(it).writeText("partial"); throw java.io.IOException("interrupted") } }.isFailure)
            val second = File(root, "second")
            assertTrue(runCatching { restore(root, source, second) }.isFailure)
            assertFalse(second.exists()); assertEquals("complete source", source.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledRestoreRetainsSourceAndPendingIntent() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "partial")
            val job = launch {
                restore(root, source, target) { File(it).writeText("partial"); throw CancellationException("cancel") }
            }
            job.join(); assertTrue(job.isCancelled)
            assertTrue(runCatching { restore(root, source, File(root, "retry")) }.isFailure)
            assertEquals("original", source.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun missingDestinationCanBeRestoredButModifiedDestinationCannot() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "restored")
            restore(root, source, target); target.delete()
            val second = File(root, "second"); restore(root, source, second)
            second.writeText("changed")
            assertTrue(runCatching { restore(root, source, File(root, "third")) }.isFailure)
            assertFalse(File(root, "third").exists()); assertEquals("changed", second.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun existingDestinationAndBrokenReceiptNeverOverwrite() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "existing").apply { writeText("existing") }
            assertTrue(runCatching { restore(root, source, target) }.isFailure)
            assertEquals("existing", target.readText())
            val receipts = File(root, "receipts").apply { mkdirs() }
            target.delete(); restore(root, source, target)
            receipts.listFiles()!!.single { it.extension == "properties" }.writeText("invalid")
            assertTrue(runCatching { restore(root, source, File(root, "other")) }.isFailure)
            assertFalse(File(root, "other").exists()); assertTrue(source.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun localSafAliasesAndSymlinksCannotTargetTrash() {
        val root = directory()
        try {
            val trash = File(root, ".bonobo_trash").apply { mkdir() }
            val volumes = mapOf("primary" to root)
            val alias = StorageEquivalence.local("com.android.externalstorage.documents", "primary:.bonobo_trash/child", volumes)!!
            assertTrue(runCatching { StorageEquivalence.requireOutsideTrash(alias, trash) }.isFailure)
            assertTrue(runCatching { StorageEquivalence.local("com.android.externalstorage.documents", "primary:../escape", volumes) }.isFailure)
            val link = File(root, "alias"); Files.createSymbolicLink(link.toPath(), trash.toPath())
            assertTrue(runCatching { StorageEquivalence.requireOutsideTrash(link, trash) }.isFailure)
            assertNull(StorageEquivalence.local("opaque.provider", "primary:elsewhere", volumes))
        } finally { root.deleteRecursively() }
    }
    @Test fun lostTruncatedOrSameSizeCorruptedNetworkCopyNeverDeletesSource() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("abcdefgh") }
            for (bytes in listOf("", "abc", "xxxxxxxx")) {
                val target = File(root, "target")
                var deleted = false
                assertTrue(runCatching {
                    IntegrityVerifier.copyThenDelete(LocalIntegrityAccess, source.path, LocalIntegrityAccess, target.path,
                        { target.writeText(bytes) }, { deleted = true; source.delete() })
                }.isFailure)
                assertFalse(deleted); assertEquals("abcdefgh", source.readText()); target.delete()
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun networkReadFailureAndSourceChangePreventDeletion() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { writeText("original") }
            val target = File(root, "target")
            var deleted = false
            assertTrue(runCatching {
                IntegrityVerifier.copyThenDelete(LocalIntegrityAccess, source.path, LocalIntegrityAccess, target.path,
                    { source.copyTo(target); source.writeText("modified") }, { deleted = true })
            }.isFailure)
            assertFalse(deleted); assertEquals("modified", source.readText())
            val unreadable = object : IntegrityVerifier.Access {
                override fun children(path: String): List<IntegrityVerifier.Child>? = null
                override fun open(path: String): java.io.InputStream = throw java.io.IOException("provider failure")
            }
            assertTrue(runCatching { IntegrityVerifier.copyThenDelete(LocalIntegrityAccess, source.path,
                unreadable, "remote", {}, { deleted = true }) }.isFailure)
            assertFalse(deleted)
        } finally { root.deleteRecursively() }
    }
    @Test fun recursiveNetworkTreeVerifiedBeforeDeletingAndCancellationPreservesSources() = runBlocking {
        val root = directory()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "child").writeText("child")
            val target = File(root, "target")
            IntegrityVerifier.copyThenDelete(LocalIntegrityAccess, source.path, LocalIntegrityAccess, target.path,
                { source.copyRecursively(target) }, { source.deleteRecursively() })
            assertFalse(source.exists()); assertEquals("child", File(target, "child").readText())
            val file = File(root, "cancel-source").apply { writeText("preserve") }
            val job = launch {
                IntegrityVerifier.copyThenDelete(LocalIntegrityAccess, file.path, LocalIntegrityAccess, "unused",
                    { throw CancellationException("cancel") }, { file.delete() })
            }
            job.join(); assertTrue(job.isCancelled); assertEquals("preserve", file.readText())
        } finally { root.deleteRecursively() }
    }
}
