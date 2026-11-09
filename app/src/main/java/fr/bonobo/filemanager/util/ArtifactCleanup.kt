package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result


/** Delete only matching receipts, in reverse creation order, and only empty directories. */
object ArtifactCleanup {
    data class Node(val path: String, val name: String, val directory: Boolean, val identity: String)
    interface Access {
        fun list(parent: String): List<Node>
        fun delete(entry: OperationArtifacts.Entry)
        fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry): Boolean = false
    }
    fun run(operation: OperationArtifacts, access: Access,
        select: (OperationArtifacts.Entry) -> Boolean = { true }): List<OperationArtifacts.Entry> {
        val diagnosticOperation = Diagnostic.operation()
        Diagnostic.event(Event.CLEANUP, Result.START, diagnosticOperation)
        for (entry in operation.entries().filter(select).asReversed()) {
            try {
                check(entry.owned) { "Création non confirmée" }
                val siblings = access.list(entry.parent)
                val node = siblings.singleOrNull { it.identity == entry.identity }
                if (node != null) {
                    check(node.path == entry.path && node.name == entry.name && node.directory == entry.directory)
                    if (entry.directory) {
                        check(access.supportsEmptyDirectoryDelete(entry))
                        check(access.list(entry.path).isEmpty())
                    }
                    access.delete(entry)
                    val after = access.list(entry.parent)
                    check(after.none { it.identity == entry.identity || it.name == entry.name })
                } else check(siblings.none { it.name == entry.name })
                operation.forget(entry)
            } catch (_: Exception) { /* Keep the receipt. No destructive retry by name. */ }
        }
        return operation.entries().filter(select).also {
            Diagnostic.event(Event.CLEANUP, if (it.isEmpty()) Result.OK else Result.INDETERMINATE, diagnosticOperation)
            if (it.isNotEmpty()) Diagnostic.detail(Event.CLEANUP, Result.INDETERMINATE, diagnosticOperation,
                kind = if (it.any { entry -> entry.directory })
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.DIRECTORY
                else fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.FILE,
                mode = fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.CLEANUP)
        }
    }
}
