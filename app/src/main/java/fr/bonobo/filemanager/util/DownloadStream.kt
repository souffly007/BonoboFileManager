package fr.bonobo.filemanager.util

import java.io.InputStream
import java.io.OutputStream

/** Bounded memory, 64-bit counts, cancellation between reads and exact size validation. */
object DownloadStream {
    fun copy(input: InputStream, output: OutputStream, expectedSize: Long,
             checkCancelled: () -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            checkCancelled()
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count.toLong()
            check(expectedSize < 0 || total <= expectedSize) { "La taille du fichier distant a changé" }
            progress(total)
        }
        check(expectedSize < 0 || total == expectedSize) { "Téléchargement incomplet : $total octets reçus sur $expectedSize" }
        output.flush()
        return total
    }
}
