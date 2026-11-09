package fr.bonobo.filemanager.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Reports only completed engine results; never substitutes permanent deletion for trash. */
object DeletionBatch {
    fun canTrash(paths: List<String>) = paths.none { it.startsWith("content://") }
    data class Outcome(val completed: List<String>, val message: String)
    suspend fun run(paths: List<String>, trash: Boolean, remove: suspend (String) -> Result<Unit>): Outcome {
        if (trash && !canTrash(paths)) {
            paths.filter { it.startsWith("content://") }.forEach {
                fr.bonobo.filemanager.diagnostic.DeletionTrace(
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage.SAF,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind.UNKNOWN,
                    fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode.TRASH
                ).end(fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result.REFUSED)
            }
            return Outcome(emptyList(), "Corbeille locale indisponible pour SAF. Aucune suppression effectuée.")
        }
        val completed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        paths.forEachIndexed { index, path ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = try { remove(path) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) completed += path
            else errors += "Élément ${index + 1} : ${result.exceptionOrNull()?.message ?: "Suppression non confirmée"}"
        }
        return Outcome(completed, "${completed.size}/${paths.size} élément(s) " +
            (if (trash) "mis à la corbeille." else "dont la disparition a été observée immédiatement. Persistance après remontage USB non vérifiée.") +
            if (errors.isEmpty()) "" else "\n${errors.joinToString("\n")}\nUne suppression de dossier refusée peut être partielle ; actualisez le dossier.")
    }
}
