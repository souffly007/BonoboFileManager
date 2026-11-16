package fr.bonobo.filemanager.domain.transfer

sealed interface StorageRef {
    val path: String
    data class Local(override val path: String) : StorageRef
    data class SafDocument(override val path: String) : StorageRef
    data class Remote(val connectionId: Long, val protocol: String, override val path: String) : StorageRef
}
/**
 * TRASH: element of another volume (USB key) moved to the internal trash through the verified copy circuit.
 * RESTORE: trash entry copied back (verified), the entry itself stays in the trash.
 */
enum class TransferOperation { COPY, MOVE, DUPLICATE, TRASH, RESTORE }
enum class TransferPhase { PREPARING, COPYING, VERIFYING, PUBLISHING, DELETING, DONE, CANCEL_REQUESTED, CANCELLED, ERROR, INTERRUPTED }
/** NOT_STARTED: refused before the first byte (free space, FAT32 limit) or never reached after a fatal error. */
enum class ItemStatus { DONE, FAILED, CANCELLED, UNCERTAIN, SKIPPED, NOT_STARTED }

/** What to do when the destination already holds an element with the same name. */
enum class ConflictAction {
    /** No decision: the motor refuses the element (DESTINATION_CONFLICT). */
    ASK,
    /** Write a verified .bonobo-part, then replace the existing element. Folders: merge and replace differing files. */
    REPLACE,
    /** Copy under « nom (1) ». */
    RENAME,
    /** Leave the destination untouched. */
    SKIP,
    /** Folders: add missing files; files already present with the same size (optionally same SHA-256) are skipped. */
    MERGE
}

/** Precise cause for the result card. Raw system messages stay in [ItemResult.detail]. */
enum class FailureKind { USB_REMOVED, DISK_FULL, INSUFFICIENT_SPACE, FILE_TOO_LARGE, CONFLICT, OTHER }

data class TransferSource(val ref: StorageRef, val name: String, val directory: Boolean,
    val conflict: ConflictAction = ConflictAction.ASK, val compareHash: Boolean = false)
/** [related]: other places written to (restoration to each element's origin), e.g. for the USB keys of « Réessayer ». */
data class TransferRequest(val sources: List<TransferSource>, val destination: StorageRef, val operation: TransferOperation,
    val related: List<StorageRef> = emptyList())
data class Inventory(val files: Long, val bytes: Long?, val largest: Long? = null, val largestName: String? = null)
data class ItemResult(val source: TransferSource, val status: ItemStatus, val message: String? = null,
    val detail: String? = null)
data class TransferState(
    val id: String = "", val active: Boolean = false, val phase: TransferPhase = TransferPhase.DONE,
    val currentName: String = "", val currentSource: String = "", val destination: String = "",
    val operation: TransferOperation = TransferOperation.COPY,
    val totalRoots: Int = 0, val processedRoots: Int = 0,
    val completedFiles: Long = 0, val totalFiles: Long? = null,
    val bytes: Long = 0, val totalBytes: Long? = null,
    val fileBytes: Long = 0, val fileTotal: Long? = null,
    val speed: Long? = null, val etaSeconds: Long? = null,
    val cancelRequested: Boolean = false, val results: List<ItemResult> = emptyList(),
    val acknowledged: Boolean = false,
    val notice: String? = null,
    val failure: FailureKind? = null,
    val failureOnUsb: Boolean = false
) {
    // 100% bytes is not completion: verification and source deletion still have to finish.
    val copyFraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
interface TransferEngine {
    suspend fun normalize(sources: List<TransferSource>): List<TransferSource> = sources.distinctBy { it.ref }
    suspend fun inspect(source: TransferSource): Inventory
    /** Refuse before writing anything (free space, file-system limits). Throw [TransferFailure]. */
    suspend fun preflight(request: TransferRequest, inventories: Map<StorageRef, Inventory>) {}
    suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation)
}

/**
 * Typed failure. [message] is short, French and shown to the user; [technical] (raw system text)
 * is only displayed in the « Informations techniques » part of the details.
 * [fatal] stops the remaining elements: continuing on a full or removed key is pointless.
 */
class TransferFailure(val kind: FailureKind, message: String, val technical: String? = null,
    val usb: Boolean = false, cause: Throwable? = null) : java.io.IOException(message, cause) {
    val fatal: Boolean get() = kind == FailureKind.DISK_FULL || kind == FailureKind.USB_REMOVED
}

/** The element was left untouched on purpose (conflict policy « Ignorer » or identical file). */
class TransferSkipped(message: String) : Exception(message)

/** Cancellation whose cleanup was verified (no partial file left behind). */
class CleanCancellation(message: String, val keptFiles: Int = 0) : kotlinx.coroutines.CancellationException(message)

/** Pure formatting, usable in JVM tests (no Android dependency). */
object TransferText {
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var value = bytes / 1024.0
        var index = 0
        while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
        return String.format(java.util.Locale.FRANCE, if (value < 10) "%.1f %s" else "%.0f %s", value, units[index])
    }
    /** « 1 erreur », « 2 erreurs », « 0 erreur ». */
    fun plural(count: Number, singular: String, plural: String = singular + "s"): String =
        "$count ${if (count.toLong() <= 1L) singular else plural}"
}
