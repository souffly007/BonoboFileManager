package fr.bonobo.filemanager.domain.transfer

import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Metadata only; iterative, no content reads, no guessed document-ID hierarchy. */
object TransferInventory {
    const val MAX_DEPTH = 128
    suspend fun inspect(access: IntegrityVerifier.Access, root: String, rootName: String? = null,
        size: (String) -> Long?): Inventory {
        // (path, depth, display name)
        val pending = java.util.ArrayDeque<Triple<String, Int, String?>>().apply { add(Triple(root, 0, rootName)) }
        val seen = mutableSetOf<String>()
        var files = 0L; var bytes: Long? = 0L
        var largest: Long? = null; var largestName: String? = null
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (path, depth, name) = pending.removeLast()
            require(depth <= MAX_DEPTH) { "Arborescence trop profonde : transfert refusé" }
            require(seen.add(access.identity(path))) { "Cycle ou alias : transfert refusé" }
            val children = access.children(path)
            if (children == null) {
                files = Math.addExact(files, 1)
                val count = size(path)?.takeIf { it >= 0 }
                bytes = if (bytes != null && count != null) Math.addExact(bytes, count) else null
                if (count != null && (largest == null || count > largest)) { largest = count; largestName = name }
            } else for (child in children) {
                require(child.name.isNotBlank() && child.name !in listOf(".", "..") && child.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Nom de document invalide" }
                pending.add(Triple(child.path, depth + 1, child.name))
            }
        }
        return Inventory(files, bytes, largest, largestName)
    }
}
