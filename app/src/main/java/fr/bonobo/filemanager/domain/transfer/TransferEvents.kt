package fr.bonobo.filemanager.domain.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional observation only: never changes a motor's integrity decisions. */
class TransferEvents(val report: (Event) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferEvents> {
        suspend fun emit(event: Event) { currentCoroutineContext()[Key]?.report?.invoke(event) }
        suspend fun observer(): ((Event) -> Unit)? = currentCoroutineContext()[Key]?.report
    }
    data class Event(val phase: TransferPhase, val path: String? = null,
        val name: String? = null, val bytesDelta: Long = 0, val fileBytes: Long? = null, val fileTotal: Long? = null, val fileStart: Boolean = false)
}

/** Monotone time, phase-local samples. ETA describes copying only, never verification/deletion. */
class TransferRate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val samples = java.util.ArrayDeque<Pair<Long, Long>>()
    private var measured: Pair<Long?, Long?> = null to null
    fun reset() { samples.clear(); measured = null to null }
    fun sample(bytes: Long, remaining: Long?): Pair<Long?, Long?> {
        val now = clock()
        if (samples.isNotEmpty() && now < samples.last.first) reset()
        if (samples.isNotEmpty() && now - samples.last.first < 200) return measured
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first.first > 5_000) samples.removeFirst()
        val first = samples.first
        val elapsed = now - first.first
        if (elapsed < 1_000 || bytes <= first.second) return null to null
        val speed = ((bytes - first.second).toDouble() * 1000 / elapsed).toLong().takeIf { it > 0 }
        val rates = samples.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.first - a.first
            if (dt >= 100 && b.second > a.second) (b.second - a.second).toDouble() * 1000 / dt else null
        }
        val stable = elapsed >= 3_000 && rates.size >= 3 && rates.minOrNull()!! >= rates.maxOrNull()!! * 0.7
        val eta = if (stable && remaining != null && remaining >= 0 && speed != null) (remaining / speed) else null
        measured = speed to eta
        return measured
    }
}
