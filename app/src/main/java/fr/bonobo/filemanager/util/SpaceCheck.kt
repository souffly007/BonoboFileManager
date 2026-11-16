package fr.bonobo.filemanager.util

import android.content.Context
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.domain.transfer.TransferText

/**
 * Free-space check done before writing the first byte, shared by every copy path: transfers
 * (batch preflight), single copies, trash moves from a USB key and trash restorations.
 * Always logged as SPACE_CHECK (expected = needed + margin, transferred = available).
 */
object SpaceCheck {
    /** Safety margin: 16 MiB + 1 % + cluster slack (up to 32 KiB per file on FAT/exFAT). */
    fun margin(needed: Long, files: Long): Long = 16L * 1024 * 1024 + needed / 100 + files * 32 * 1024

    fun enough(needed: Long, files: Long, available: Long): Boolean = needed + margin(needed, files) <= available

    /**
     * French message for the user (pure, unit-tested).
     * @param replacing « Remplacer »: the old version is removed only after the new one is copied and
     *   verified, so the full size is needed even though the final result takes the same room.
     */
    fun message(usb: Boolean, needed: Long, available: Long, replacing: Boolean): String {
        val place = if (usb) "la clé USB" else "le stockage de destination"
        return if (replacing)
            "Impossible de remplacer : la nouvelle version (${TransferText.size(needed)}) doit être entièrement copiée " +
                "et vérifiée avant que l’ancienne soit supprimée, mais seulement ${TransferText.size(available)} sont libres " +
                "sur $place. Libérez de l’espace, ou supprimez d’abord l’ancienne version."
        else "${TransferText.size(needed)} à copier, ${TransferText.size(available)} libres sur $place. " +
            "Libérez de l’espace ou copiez moins d’éléments."
    }

    /**
     * Refuse (TransferFailure INSUFFICIENT_SPACE) when [destination]'s volume lacks room for [needed]
     * bytes in [files] files. Unknown free space is logged (UNKNOWN) and not blocking: DISK_FULL
     * detection during the write still applies.
     */
    fun require(context: Context, destination: String, needed: Long, files: Long,
        operation: java.util.UUID? = null, mode: Mode = Mode.COPY, replacing: Boolean = false,
        storage: Storage = UsbVolumes.storage(context, destination)) {
        val volume = runCatching { UsbVolumes.forPath(context, destination) }.getOrNull()
        val usb = UsbVolumes.isUsb(volume)
        val available = runCatching { UsbVolumes.availableBytes(context, destination) }.getOrNull()
        if (available == null) {
            Diagnostic.detail(Event.SPACE_CHECK, Result.UNKNOWN, operation, storage, mode = mode,
                expected = needed + margin(needed, files))
            return
        }
        val ok = enough(needed, files, available)
        Diagnostic.detail(Event.SPACE_CHECK, if (ok) Result.OK else Result.INSUFFICIENT_SPACE, operation, storage,
            mode = mode, expected = needed + margin(needed, files), transferred = available)
        if (!ok) throw TransferFailure(FailureKind.INSUFFICIENT_SPACE, message(usb, needed, available, replacing), usb = usb)
    }

    /** Bytes and file count of a local file or tree (symbolic links are not followed). */
    fun localNeed(file: java.io.File): Pair<Long, Long> {
        var bytes = 0L; var files = 0L
        fun walk(f: java.io.File) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return
            if (f.isDirectory) f.listFiles()?.forEach(::walk)
            else { bytes += f.length(); files++ }
        }
        walk(file)
        return bytes to files
    }
}
