package fr.bonobo.filemanager.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One reusable session per key (SMB: host, port, share, user, secret), shared by every operation.
 *  - [open] is called only when no live session exists (first use, session dropped, after [invalidate]);
 *  - at most [maxConcurrent] operations run at the same time on one session;
 *  - a failure for which [broken] is true drops the session; [use] with retry = true then runs once more
 *    on a fresh session (only for operations that can safely be repeated, e.g. a listing);
 *  - [sweep] closes sessions unused for [idleMillis]; [release] closes a session at once when no
 *    operation uses it, otherwise when its last running operation ends. A new operation started in the
 *    meantime (next element of a transfer) cancels the request: the session then closes [idleMillis]
 *    after its last use, so a multi-element transfer does not reconnect for every element.
 * Pure (no Android, no network): unit-tested with fake resources.
 */
class SessionPool<K : Any, R : Any>(
    private val open: (K) -> R,
    private val alive: (R) -> Boolean,
    private val close: (R) -> Unit,
    private val broken: (Throwable) -> Boolean = { false },
    private val idleMillis: Long = 60_000,
    private val maxConcurrent: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Lock order: [slots] before [Slot.lock], never the reverse. */
    private inner class Slot(val key: K) {
        val permits = Semaphore(maxConcurrent)
        val lock = Any()
        var resource: R? = null
        var users = 0
        var lastUsed = clock()
        var releaseRequested = false
    }

    private val slots = HashMap<K, Slot>()

    suspend fun <T> use(key: K, retry: Boolean = false, block: suspend (R) -> T): T {
        val slot = synchronized(slots) {
            slots.getOrPut(key) { Slot(key) }.also { it.users++; it.releaseRequested = false }
        }
        try {
            return slot.permits.withPermit {
                val first = acquire(slot)
                try {
                    block(first)
                } catch (e: Throwable) {
                    if (e is CancellationException || !(broken(e) || !safeAlive(first))) throw e
                    drop(slot, first)
                    if (!retry) throw e
                    val second = acquire(slot)
                    try { block(second) } catch (again: Throwable) {
                        if (again !is CancellationException && (broken(again) || !safeAlive(second))) drop(slot, second)
                        throw again
                    }
                }
            }
        } finally {
            synchronized(slots) {
                slot.users--
                slot.lastUsed = clock()
                if (slot.users == 0 && slot.releaseRequested) closeSlot(slot)
            }
        }
    }

    /** Live session of [slot], opened (or reopened after a drop) when needed. Blocking: call on an IO thread. */
    private fun acquire(slot: Slot): R = synchronized(slot.lock) {
        slot.resource?.let { current -> if (safeAlive(current)) return current; slot.resource = null; safeClose(current) }
        open(slot.key).also { slot.resource = it }
    }

    private fun drop(slot: Slot, resource: R) = synchronized(slot.lock) {
        if (slot.resource === resource) slot.resource = null
        safeClose(resource)
    }

    /** Drops the session of [key] (e.g. credentials changed); the next operation reconnects. */
    fun invalidate(key: K) {
        val slot = synchronized(slots) { slots[key] } ?: return
        synchronized(slot.lock) { slot.resource?.let { slot.resource = null; safeClose(it) } }
    }

    /** Closes the session of [key] now if unused, otherwise as soon as its last operation ends. */
    fun release(key: K) = synchronized(slots) {
        val slot = slots[key] ?: return@synchronized
        if (slot.users == 0) closeSlot(slot) else slot.releaseRequested = true
    }

    /** Closes sessions unused for [idleMillis]. */
    fun sweep() = synchronized(slots) {
        val now = clock()
        slots.entries.filter { it.value.users == 0 && now - it.value.lastUsed >= idleMillis }
            .forEach { closeSlot(it.value) }
    }

    fun closeAll() = synchronized(slots) {
        slots.values.filter { it.users == 0 }.forEach { closeSlot(it) }
        slots.values.forEach { it.releaseRequested = true }
    }

    /** Open sessions (for tests and diagnostics). */
    fun openCount(): Int = synchronized(slots) { slots.values.count { synchronized(it.lock) { it.resource != null } } }

    private fun closeSlot(slot: Slot) {
        if (slots[slot.key] === slot) slots.remove(slot.key)
        synchronized(slot.lock) { slot.resource?.let { slot.resource = null; safeClose(it) } }
    }

    private fun safeAlive(resource: R) = runCatching { alive(resource) }.getOrDefault(false)
    private fun safeClose(resource: R) { runCatching { close(resource) } }
}
