package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result


import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VerifiedMove {
    suspend fun finish(destination: String, retainSource: Boolean, verify: suspend () -> Unit,
        delete: suspend () -> Unit) {
        val operation = Diagnostic.operation()
        Diagnostic.event(Event.MOVE, Result.START, operation)
        try {
            try { verify() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                throw IOException("Copie effectuée, déplacement incomplet : vérification finale insuffisante ; source conservée. ${e.message.orEmpty()}", e)
            }
            currentCoroutineContext().ensureActive()
            if (retainSource) throw IOException("Copie vérifiée — source conservée par sécurité. Déplacement non terminé. Persistance après reconnexion non testée.")
            try { delete() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                throw IOException("Copie effectuée, déplacement incomplet ; source conservée ou partiellement supprimée : suppression source non confirmée. ${e.message.orEmpty()}", e)
            }
            Diagnostic.event(Event.MOVE, Result.OK, operation)
        } catch (e: Exception) {
            Diagnostic.event(Event.SOURCE_RETAINED, Result.INDETERMINATE, operation)
            throw e
        }
    }
}
