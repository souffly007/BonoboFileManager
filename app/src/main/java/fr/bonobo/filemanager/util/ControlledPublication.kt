package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result


import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No publication before stage verification; no completion before published verification. */
object ControlledPublication {
    suspend fun run(stage: suspend () -> String, publish: suspend (String) -> String,
        verify: suspend (String) -> Unit): String {
        val operation = Diagnostic.operation()
        Diagnostic.event(Event.PUBLICATION, Result.START, operation)
        try {
            val temporary = stage()
            currentCoroutineContext().ensureActive()
            TransferEvents.emit(TransferEvents.Event(TransferPhase.PUBLISHING))
            val published = publish(temporary)
            currentCoroutineContext().ensureActive()
            TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            verify(published)
            currentCoroutineContext().ensureActive()
            Diagnostic.event(Event.PUBLICATION, Result.OK, operation)
            return published
        } catch (e: Exception) { Diagnostic.event(Event.PUBLICATION, Result.ERROR, operation); throw e }
    }
}
