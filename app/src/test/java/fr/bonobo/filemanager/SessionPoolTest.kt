package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SessionPool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SessionPoolTest {

    private class Fake(val id: Int) { var alive = true; var closed = false }
    private class Lost : IOException("session perdue")

    private val opened = mutableListOf<Fake>()
    private var now = 0L

    private fun pool(maxConcurrent: Int = 4, idle: Long = 60_000) = SessionPool<String, Fake>(
        open = { synchronized(opened) { Fake(opened.size + 1).also { opened += it } } },
        alive = { it.alive && !it.closed },
        close = { it.closed = true },
        broken = { it is Lost },
        idleMillis = idle,
        maxConcurrent = maxConcurrent,
        clock = { now }
    )

    @Test
    fun `une seule session reutilisee par connexion`() = runBlocking {
        val pool = pool()
        val first = pool.use("nas|Public|nico") { it.id }
        val second = pool.use("nas|Public|nico") { it.id }
        val third = pool.use("nas|Public|nico") { it.id }
        assertEquals(1, opened.size)
        assertEquals(listOf(1, 1, 1), listOf(first, second, third))
        assertEquals(1, pool.openCount())
    }

    @Test
    fun `connexions differentes sessions differentes`() = runBlocking {
        val pool = pool()
        pool.use("nas|Public|nico") { }
        pool.use("nas|Public|invite") { }
        assertEquals(2, opened.size)
    }

    @Test
    fun `session tombee rouverte automatiquement`() = runBlocking {
        val pool = pool()
        pool.use("k") { }
        opened[0].alive = false
        val id = pool.use("k") { it.id }
        assertEquals(2, id)
        assertTrue(opened[0].closed)
    }

    @Test
    fun `perte de session pendant une operation repetable relance une fois`() = runBlocking {
        val pool = pool()
        val calls = AtomicInteger()
        val result = pool.use("k", retry = true) { session ->
            if (calls.incrementAndGet() == 1) { session.alive = false; throw Lost() }
            "liste sur session ${session.id}"
        }
        assertEquals("liste sur session 2", result)
        assertEquals(2, calls.get())
        assertTrue(opened[0].closed)
    }

    @Test
    fun `operation non repetable non relancee mais session remplacee`() = runBlocking {
        val pool = pool()
        val calls = AtomicInteger()
        try { pool.use("k") { calls.incrementAndGet(); throw Lost() }; fail() } catch (_: Lost) { }
        assertEquals(1, calls.get())
        assertEquals(2, pool.use("k") { it.id })
    }

    @Test
    fun `erreur ordinaire garde la session`() = runBlocking {
        val pool = pool()
        try { pool.use("k") { throw IllegalStateException("accès refusé") } ; fail() } catch (_: IllegalStateException) { }
        assertEquals(1, pool.use("k") { it.id })
        assertEquals(1, opened.size)
    }

    @Test
    fun `nombre limite d'operations simultanees`() = runBlocking {
        val pool = pool(maxConcurrent = 2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        (1..8).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                pool.use("k") {
                    val current = running.incrementAndGet()
                    peak.accumulateAndGet(current, ::maxOf)
                    delay(20)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(2, peak.get())
        assertEquals(1, opened.size)
    }

    @Test
    fun `fermee apres inactivite`() = runBlocking {
        val pool = pool(idle = 60_000)
        pool.use("k") { }
        now = 30_000; pool.sweep()
        assertFalse(opened[0].closed)
        now = 61_000; pool.sweep()
        assertTrue(opened[0].closed)
        assertEquals(0, pool.openCount())
        assertEquals(2, pool.use("k") { it.id })
    }

    @Test
    fun `sortie de l'explorateur ferme la session libre`() = runBlocking {
        val pool = pool()
        pool.use("k") { }
        pool.release("k")
        assertTrue(opened[0].closed)
    }

    @Test
    fun `sortie pendant un transfert ferme a la fin du transfert`() = runBlocking {
        val pool = pool()
        pool.use("k") { session ->
            pool.release("k")
            assertFalse("le transfert en cours garde sa session", session.closed)
            pool.use("k") { inner -> assertEquals(session.id, inner.id) }
        }
        // A new use cancelled the release request: still open; release again → closed.
        assertFalse(opened[0].closed)
        pool.release("k")
        assertTrue(opened[0].closed)
    }
}
