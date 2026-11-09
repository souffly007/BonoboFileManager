package fr.bonobo.filemanager.domain.transfer

sealed interface StorageRef {
    val path: String
    data class Local(override val path: String) : StorageRef
    data class SafDocument(override val path: String) : StorageRef
    data class Remote(val connectionId: Long, val protocol: String, override val path: String) : StorageRef
}
enum class TransferOperation { COPY, MOVE, DUPLICATE }
enum class TransferPhase { PREPARING, COPYING, VERIFYING, PUBLISHING, DELETING, DONE, CANCEL_REQUESTED, CANCELLED, ERROR, INTERRUPTED }
enum class ItemStatus { DONE, FAILED, CANCELLED, UNCERTAIN }
data class TransferSource(val ref: StorageRef, val name: String, val directory: Boolean)
data class TransferRequest(val sources: List<TransferSource>, val destination: StorageRef, val operation: TransferOperation)
data class Inventory(val files: Long, val bytes: Long?)
data class ItemResult(val source: TransferSource, val status: ItemStatus, val message: String? = null)
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
    val notice: String? = null
) {
    // 100% bytes is not completion: verification and source deletion still have to finish.
    val copyFraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
interface TransferEngine {
    suspend fun normalize(sources: List<TransferSource>): List<TransferSource> = sources.distinctBy { it.ref }
    suspend fun inspect(source: TransferSource): Inventory
    suspend fun execute(source: TransferSource, destination: StorageRef, operation: TransferOperation)
}
