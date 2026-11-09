package fr.bonobo.filemanager.domain.transfer

import fr.bonobo.filemanager.util.IntegrityVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Metadata only; iterative, no content reads, no guessed document-ID hierarchy. */
object TransferInventory {
    const val MAX_DEPTH = 128
    suspend fun inspect(access: IntegrityVerifier.Access, root: String, size: (String) -> Long?): Inventory {
        val pending = java.util.ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
        val seen = mutableSetOf<String>()
        var files = 0L; var bytes: Long? = 0L
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (path, depth) = pending.removeLast()
            require(depth <= MAX_DEPTH) { "Arborescence trop profonde : transfert refusé" }
            require(seen.add(access.identity(path))) { "Cycle ou alias : transfert refusé" }
            val children = access.children(path)
            if (children == null) {
                files = Math.addExact(files, 1)
                val count = size(path)?.takeIf { it >= 0 }
                bytes = if (bytes != null && count != null) Math.addExact(bytes, count) else null
            } else for (child in children) {
                require(child.name.isNotBlank() && child.name !in listOf(".", "..") && child.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Nom de document invalide" }
                pending.add(child.path to depth + 1)
            }
        }
        return Inventory(files, bytes)
    }
}
