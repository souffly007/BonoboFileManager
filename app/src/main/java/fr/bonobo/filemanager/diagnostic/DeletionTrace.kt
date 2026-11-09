package fr.bonobo.filemanager.diagnostic

import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage

/** One anonymous attempt, including failures before the destructive call. */
class DeletionTrace(private val storage: Storage, private val kind: Kind, private val mode: Mode) {
    private val operation = Diagnostic.operation()
    private val started = System.nanoTime()
    private var finished = false
    init { emit(Event.DELETE_REQUEST, Result.START, Phase.REQUEST) }
    private fun emit(event: Event, result: Result, phase: Phase) = Diagnostic.detail(event, result,
        operation, storage, kind, mode, phase, (System.nanoTime() - started) / 1_000_000)
    fun start() = emit(Event.DELETE_START, Result.START, Phase.PROVIDER)
    fun provider(result: Result) = emit(Event.DELETE_PROVIDER_RESULT, result, Phase.PROVIDER)
    fun verify(result: Result) = emit(Event.DELETE_VERIFY, result, Phase.VERIFY)
    @Synchronized fun end(result: Result) {
        if (!finished) { finished = true; emit(Event.DELETE_END, result, Phase.FINISH) }
    }
}
