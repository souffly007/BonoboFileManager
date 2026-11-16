package fr.bonobo.filemanager.util

/**
 * Recognises system failures through the whole cause chain. Providers often wrap the
 * original [android.system.ErrnoException] into IOException("write failed: ENOSPC (No space left on device)").
 */
object CopyFailures {
    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.take(16)

    private fun errno(e: Throwable): Int? = try {
        (e as? android.system.ErrnoException)?.errno
    } catch (_: Throwable) { null } // android.system absent in plain JVM tests

    private fun matches(e: Throwable, code: () -> Int, vararg markers: String): Boolean = causes(e).any { cause ->
        val number = errno(cause)
        (number != null && runCatching { number == code() }.getOrDefault(false)) ||
            markers.any { marker -> cause.message?.contains(marker, ignoreCase = true) == true }
    }

    fun isNoSpace(e: Throwable): Boolean =
        matches(e, { android.system.OsConstants.ENOSPC }, "ENOSPC", "No space left")

    /** FAT32: 4 GiB - 1 per file. Linux reports EFBIG « File too large ». */
    fun isFileTooLarge(e: Throwable): Boolean =
        matches(e, { android.system.OsConstants.EFBIG }, "EFBIG", "File too large")

    /** Errors typical of a vanished block device or provider. */
    fun looksLikeDeviceLoss(e: Throwable): Boolean = causes(e).any { cause ->
        val number = errno(cause)
        val known = runCatching {
            number != null && number in listOf(android.system.OsConstants.EIO, android.system.OsConstants.ENODEV,
                android.system.OsConstants.ENXIO, android.system.OsConstants.ENOENT, android.system.OsConstants.ENOTCONN)
        }.getOrDefault(false)
        known || cause is java.io.FileNotFoundException || listOf("EIO", "I/O error", "ENODEV", "ENOTCONN",
            "Transport endpoint", "No such device", "ENOENT", "not mounted", "déconnecté", "Support déconnecté")
            .any { cause.message?.contains(it, ignoreCase = true) == true }
    }

    /** Raw technical text, for the « Informations techniques » section only. */
    fun technical(e: Throwable): String = causes(e).mapNotNull { cause ->
        cause.message?.takeIf { it.isNotBlank() }?.let { "${cause.javaClass.simpleName}: $it" }
    }.distinct().take(3).joinToString(" ← ")
}
