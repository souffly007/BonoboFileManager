package fr.bonobo.filemanager.diagnostic

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import fr.bonobo.filemanager.util.VerifiedStreamCopy
import kotlinx.coroutines.runBlocking

class TechnicalJournalTest {
    private fun directory() = Files.createTempDirectory("diagnostic-test").toFile()
    @Test fun disabledNeverCreatesFiles() {
        val dir = directory().resolve("absent")
        val journal = TechnicalJournal(dir)
        repeat(100) { journal.record(TechnicalJournal.Event.COPY_START) }
        journal.awaitIdle()
        assertFalse(dir.exists())
        assertNull(journal.operation())
    }
    @Test fun enableDisableAndClear() {
        val dir = directory()
        val journal = TechnicalJournal(dir)
        journal.setEnabled(true)
        assertNotNull(journal.operation())
        journal.record(TechnicalJournal.Event.COPY_START)
        journal.awaitIdle()
        val before = dir.resolve("current.log").readBytes()
        journal.setEnabled(false)
        journal.record(TechnicalJournal.Event.DELETE)
        journal.awaitIdle()
        assertArrayEquals(before, dir.resolve("current.log").readBytes())
        assertTrue(journal.clear())
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun rotationAndExportAreBoundedAndChronological() {
        val dir = directory()
        val journal = TechnicalJournal(dir, 2000)
        journal.setEnabled(true)
        repeat(100) { journal.record(TechnicalJournal.Event.COPY_END, transferred = it.toLong()); journal.awaitIdle() }
        assertTrue(dir.resolve("previous.log").exists())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2000)
        val out = ByteArrayOutputStream()
        assertTrue(journal.export(out))
        assertTrue(out.toString().contains("transferred=99"))
        assertTrue(out.size() <= 2000)
    }
    @Test fun vocabularyHasNoSensitiveStringOrThrowableParameter() {
        val method = TechnicalJournal::class.java.declaredMethods.single { it.name == "record" }
        assertFalse(method.parameterTypes.contains(String::class.java))
        assertFalse(method.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) })
        val journal = TechnicalJournal(directory())
        journal.setEnabled(true)
        journal.record(TechnicalJournal.Event.SHA256, TechnicalJournal.Result.MATCH, journal.operation(), 123, 123, 5)
        journal.awaitIdle()
        val out = ByteArrayOutputStream(); assertTrue(journal.export(out))
        assertTrue(out.toString().matches(Regex("[0-9]+ SHA256 MATCH op=[0-9a-f-]{36} expected=123 transferred=123 durationMs=5\n")))
    }
    @Test fun failingStorageDoesNotAffectCopyOrVerification() = runBlocking {
        val badDirectory = Files.createTempFile("diagnostic", ".file").toFile()
        val journal = TechnicalJournal(badDirectory)
        journal.setEnabled(true)
        Diagnostic.journal = journal
        try {
            val output = ByteArrayOutputStream()
            val bytes = ByteArray(200000) { it.toByte() }
            val evidence = VerifiedStreamCopy.write({ bytes.inputStream() }, { output }, bytes.size.toLong())
            VerifiedStreamCopy.verify({ output.toByteArray().inputStream() }, evidence, bytes.size.toLong())
            journal.awaitIdle()
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(bytes.size.toLong(), evidence.bytes)
            assertFalse(badDirectory.isDirectory)
        } finally { Diagnostic.journal = null }
    }
    @Test fun stalledExportAndFullQueueNeverBlockProducer() {
        val journal = TechnicalJournal(directory()); journal.setEnabled(true)
        journal.record(TechnicalJournal.Event.ENABLED); journal.awaitIdle()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val exporting = threads.submit<Boolean> { journal.export(object : java.io.OutputStream() {
                override fun write(value: Int) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            }) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val producing = threads.submit { repeat(10000) { journal.record(TechnicalJournal.Event.COPY_START) } }
            producing.get(2, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown(); assertTrue(exporting.get(5, java.util.concurrent.TimeUnit.SECONDS))
            journal.awaitIdle()
        } finally { release.countDown(); threads.shutdownNow() }
    }
    @Test fun exportFailureIsReportedAndClearRemovesBothSegments() {
        val dir = directory(); val journal = TechnicalJournal(dir, 2000); journal.setEnabled(true)
        repeat(30) { journal.record(TechnicalJournal.Event.ENABLED); journal.awaitIdle() }
        assertFalse(journal.export(object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("private-secret") }
        }))
        assertTrue(journal.clear()); assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun mismatchIsLoggedWithoutHashOrException() = runBlocking {
        val journal = TechnicalJournal(directory()); journal.setEnabled(true); Diagnostic.journal = journal
        try {
            val evidence = VerifiedStreamCopy.write({ byteArrayOf(1).inputStream() }, { ByteArrayOutputStream() }, 1)
            try { VerifiedStreamCopy.verify({ byteArrayOf(2).inputStream() }, evidence, 1); fail() }
            catch (_: IllegalStateException) { }
            journal.awaitIdle()
            val out = ByteArrayOutputStream(); journal.export(out)
            assertTrue(out.toString().contains("SHA256 MISMATCH"))
            assertFalse(out.toString().contains("Exception"))
        } finally { Diagnostic.journal = null }
    }
}
