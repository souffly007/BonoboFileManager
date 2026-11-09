package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.DeletionTrace
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage


import java.io.IOException

/** Provider acknowledgements alone are not evidence of an observed mutation. */
class SafMutations(private val access: Access) {
    data class Node(val path: String, val name: String, val directory: Boolean)
    interface Access {
        fun identity(path: String): String
        fun list(parent: String): List<Node>
        fun create(parent: String, name: String, directory: Boolean, mime: String): String
        fun delete(path: String): Boolean
        fun rename(path: String, name: String): String?
    }
    enum class Deletion { CONFIRMED, REFUSED, INDETERMINATE }
    data class DeleteResult(val state: Deletion, val cause: Exception? = null)

    fun create(parent: String, name: String, directory: Boolean, mime: String,
        beforeCreate: () -> Unit = {}, record: (Node) -> Unit = {}): String {
        val before = access.list(parent)
        if (before.any { it.name == name }) {
            Diagnostic.detail(Event.DESTINATION_CONFLICT, Result.CONFLICT, storage = Storage.SAF,
                kind = if (directory) Kind.DIRECTORY else Kind.FILE, phase = Phase.CREATE)
            error("Destination déjà occupée. Choisissez un autre nom ou examinez le dossier partiel avant une nouvelle copie ; aucun remplacement automatique.")
        }
        beforeCreate() // A provider can create a document and throw without returning its identity.
        val path = access.create(parent, name, directory, mime)
        check(before.none { access.identity(it.path) == access.identity(path) }) {
            "Création incorrecte : identité préexistante ; aucune écriture ni suppression autorisée"
        }
        // Record before the confirmation query, which can itself fail after a real creation.
        record(Node(path, name, directory))
        val after = access.list(parent)
        val node = after.singleOrNull { access.identity(it.path) == access.identity(path) }
            ?: throw IOException("Création indéterminée : $path ; dossier : $parent")
        check(node.name == name && node.directory == directory && after.count { it.name == name } == 1) {
            "Création incorrecte : nom ou type inattendu ; document : $path ; dossier : $parent"
        }
        Diagnostic.detail(Event.CREATE_RESULT, Result.OK, storage = Storage.SAF,
            kind = if (directory) Kind.DIRECTORY else Kind.FILE, phase = Phase.CREATE)
        return node.path
    }

    fun delete(path: String, parent: String, trace: DeletionTrace? = null): DeleteResult {
        val operation = Diagnostic.operation()
        Diagnostic.event(Event.DELETE, Result.START, operation)
        val result = deleteObserved(path, parent, trace)
        Diagnostic.event(Event.DISAPPEARANCE, when (result.state) {
            Deletion.CONFIRMED -> Result.OK
            Deletion.REFUSED -> Result.REFUSED
            Deletion.INDETERMINATE -> Result.INDETERMINATE
        }, operation)
        return result
    }
    private fun deleteObserved(path: String, parent: String, trace: DeletionTrace?): DeleteResult {
        // An inaccessible parent must stop us before the destructive call.
        val original = try { access.list(parent).singleOrNull { access.identity(it.path) == access.identity(path) }
            ?: return DeleteResult(Deletion.INDETERMINATE).also { trace?.verify(Result.INDETERMINATE) } }
        catch (e: Exception) { return DeleteResult(Deletion.INDETERMINATE, e).also { trace?.verify(Result.INDETERMINATE) } }
        try {
            if (!access.delete(path)) return DeleteResult(Deletion.REFUSED).also {
                trace?.provider(Result.REFUSED); trace?.verify(Result.INDETERMINATE)
            }
            trace?.provider(Result.OK)
        }
        catch (e: SecurityException) { return DeleteResult(Deletion.REFUSED, e).also {
            trace?.provider(Result.REFUSED); trace?.verify(Result.INDETERMINATE)
        } }
        catch (e: Exception) { return DeleteResult(Deletion.INDETERMINATE, e).also {
            trace?.provider(Result.INDETERMINATE); trace?.verify(Result.INDETERMINATE)
        } }
        return try {
            val after = access.list(parent)
            if (after.any { access.identity(it.path) == access.identity(path) || it.name == original.name })
                DeleteResult(Deletion.INDETERMINATE)
            else DeleteResult(Deletion.CONFIRMED)
        } catch (e: Exception) { DeleteResult(Deletion.INDETERMINATE, e) }.also {
            trace?.verify(if (it.state == Deletion.CONFIRMED) Result.OK else Result.INDETERMINATE)
        }
    }

    fun rename(path: String, parent: String, name: String, update: (String) -> Unit): String {
        val before = access.list(parent)
        val old = before.singleOrNull { access.identity(it.path) == access.identity(path) }
            ?: throw IOException("Document à renommer non confirmé")
        if (old.name == name) return old.path
        check(before.none { it.name == name }) { "Un fichier portant ce nom existe déjà" }
        val renamed = access.rename(path, name) ?: throw IOException("Renommage refusé")
        check(before.none { access.identity(it.path) != access.identity(path) && access.identity(it.path) == access.identity(renamed) }) {
            "Renommage incorrect : identité préexistante ; document retourné : $renamed"
        }
        // Keep the returned identity even when post-validation fails. Never retry using the old ID.
        update(renamed)
        val after = access.list(parent)
        val node = after.singleOrNull { access.identity(it.path) == access.identity(renamed) }
            ?: throw IOException("Renommage indéterminé : $renamed ; dossier : $parent")
        check(node.name == name && node.directory == old.directory && after.count { it.name == name } == 1 &&
            (access.identity(path) == access.identity(renamed) || after.none { access.identity(it.path) == access.identity(path) })) {
            "Renommage non confirmé : $renamed ; dossier : $parent"
        }
        return node.path
    }
}
