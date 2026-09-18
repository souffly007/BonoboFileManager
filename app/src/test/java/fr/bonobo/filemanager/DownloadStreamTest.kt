package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.DownloadStream
import java.io.*
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DownloadStreamTest {
    @Test fun fiveGiBUsesLongCountersAndBoundedBuffers() {
        val size = 5L * 1024 * 1024 * 1024 + 17
        var remaining = size
        var written = 0L
        var reported = 0L
        val input = object : InputStream() {
            override fun read(): Int = error("Use a buffer")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue(b.size <= 256 * 1024)
                if (remaining == 0L) return -1
                val n = minOf(remaining, len.toLong()).toInt()
                remaining -= n
                return n
            }
        }
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Use a buffer")
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        assertEquals(size, DownloadStream.copy(input, output, size, progress = { reported = it }))
        assertEquals(size, written); assertEquals(size, reported)
    }
    @Test fun truncatedTransferIsNotSuccessful() {
        assertThrows(IllegalStateException::class.java) {
            DownloadStream.copy(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3)
        }
    }
    @Test fun cancellationStopsBeforeAnotherChunk() {
        var cancel = false
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            DownloadStream.copy(ByteArrayInputStream(ByteArray(1024 * 1024)), output, 1024 * 1024L,
                checkCancelled = { if (cancel) throw CancellationException() }, progress = { cancel = true })
        }
        assertEquals(256 * 1024, output.size())
    }
    @Test fun storageFailurePropagates() {
        val output = object : OutputStream() { override fun write(value: Int) { throw IOException("Disk full") } }
        assertThrows(IOException::class.java) { DownloadStream.copy(ByteArrayInputStream(byteArrayOf(1)), output, 1) }
    }
}
