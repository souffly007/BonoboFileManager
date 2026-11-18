package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbErrors
import fr.bonobo.filemanager.data.repository.SmbTransfers
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.util.OperationArtifacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class SmbTransfersTest {

    private lateinit var receipts: File
    private lateinit var transfers: SmbTransfers
    private val disk = InMemorySmbDisk()
    private val volume = SmbTransfers.volume(7, "Public")

    @Before fun setUp() {
        receipts = File(System.getProperty("java.io.tmpdir"), "smb-receipts-" + System.nanoTime()).apply { mkdirs() }
        transfers = SmbTransfers(receipts)
    }

    @After fun tearDown() { receipts.deleteRecursively(); Diagnostic.journal = null }

    private fun parts() = disk.names().filter { it.endsWith(OperationArtifacts.PART_SUFFIX) }
    private fun receiptFiles() = receipts.listFiles { f -> f.extension == "properties" }.orEmpty().toList()

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (e: Throwable) { if (e is T) return e; throw e }
        fail("${T::class.simpleName} attendu"); throw IllegalStateException()
    }

    // ─────────────────────────────── Copies serveur → serveur ───────────────────────────────

    @Test
    fun `copie d'un fichier via bonobo-part puis renommage`() = runBlocking {
        disk.folder("Photos"); disk.folder("Archives"); disk.file("Photos\\a.txt", "bonjour")
        transfers.copy(disk, volume, "Photos\\a.txt", "Archives\\a.txt", move = false)
        assertEquals("bonjour", disk.text("Archives\\a.txt"))
        assertEquals("bonjour", disk.text("Photos\\a.txt"))
        assertTrue(parts().isEmpty())
        assertTrue("aucun reçu ne reste après un succès", receiptFiles().isEmpty())
    }

    @Test
    fun `nom du partiel contient l'operation`() {
        assertEquals("Archives\\.a.txt.1a2b3c4d.bonobo-part", transfers.partOf("Archives\\a.txt", "1a2b3c4d"))
        assertEquals(".a.txt.1a2b3c4d.bonobo-part", transfers.partOf("a.txt", "1a2b3c4d"))
    }

    @Test
    fun `copie d'un dossier complet`() = runBlocking {
        disk.folder("Projet"); disk.folder("Projet\\src"); disk.file("Projet\\src\\main.kt", "fun main()"); disk.file("Projet\\lisez-moi", "x")
        transfers.copy(disk, volume, "Projet", "Projet (1)", move = false)
        assertEquals("fun main()", disk.text("Projet (1)\\src\\main.kt"))
        assertEquals("x", disk.text("Projet (1)\\lisez-moi"))
        assertTrue(parts().isEmpty()); assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `deplacement supprime la source apres verification`() = runBlocking {
        disk.folder("A"); disk.folder("B"); disk.file("A\\f.bin", "données")
        transfers.copy(disk, volume, "A\\f.bin", "B\\f.bin", move = true)
        assertEquals("données", disk.text("B\\f.bin"))
        assertFalse(disk.exists("A\\f.bin"))
    }

    @Test
    fun `erreur pendant l'ecriture le partiel est supprime et verifie absent`() = runBlocking {
        disk.folder("A"); disk.folder("B"); disk.file("A\\gros.bin", "x".repeat(5000))
        disk.failWriteAfter = 1200
        val failure = expect<SmbTransfers.Failed> { runBlocking { transfers.copy(disk, volume, "A\\gros.bin", "B\\gros.bin", move = false) } }
        assertFalse(failure.leftover)
        assertTrue("partiel supprimé : ${disk.names()}", parts().isEmpty())
        assertFalse(disk.exists("B\\gros.bin"))
        assertTrue(receiptFiles().isEmpty())
        assertEquals("x".repeat(5000), disk.text("A\\gros.bin"))
    }

    @Test
    fun `annulation le partiel est supprime et l'annulation remonte`() = runBlocking {
        disk.folder("A"); disk.folder("B"); disk.file("A\\film.mkv", "y".repeat(4000))
        disk.failWriteAfter = 100
        disk.writeFailure = { CancellationException("Annulé") }
        expect<CancellationException> { runBlocking { transfers.copy(disk, volume, "A\\film.mkv", "B\\film.mkv", move = false) } }
        assertTrue(parts().isEmpty())
        assertFalse(disk.exists("B\\film.mkv"))
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `SHA different le partiel est supprime et rien n'est publie`() = runBlocking {
        disk.folder("A"); disk.folder("B"); disk.file("A\\doc.pdf", "contenu original")
        disk.corruptWrites = true
        val failure = expect<SmbTransfers.Failed> { runBlocking { transfers.copy(disk, volume, "A\\doc.pdf", "B\\doc.pdf", move = false) } }
        assertTrue(failure.message!!.contains("SHA-256"))
        assertTrue(parts().isEmpty())
        assertFalse(disk.exists("B\\doc.pdf"))
    }

    @Test
    fun `dossier interrompu dossiers vides supprimes dossiers verifies conserves`() = runBlocking {
        disk.folder("Src"); disk.folder("Src\\a"); disk.folder("Src\\b")
        disk.file("Src\\a\\1.txt", "un")            // copied and verified before the failure
        disk.file("Src\\b\\2.txt", "z".repeat(3000)) // fails in the middle
        disk.failWriteAfter = 1000
        expect<SmbTransfers.Failed> { runBlocking { transfers.copy(disk, volume, "Src", "Dst", move = false) } }
        assertTrue(parts().isEmpty())
        assertEquals("un", disk.text("Dst\\a\\1.txt"))          // KEPT_VERIFIED
        assertFalse("sous-dossier resté vide supprimé", disk.exists("Dst\\b"))
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `serveur perdu apres l'echec le partiel est retire a la connexion suivante`() = runBlocking {
        disk.folder("A"); disk.folder("B"); disk.file("A\\f.iso", "i".repeat(3000))
        disk.failWriteAfter = 500
        disk.unreachableAfterWriteFailure = true
        val failure = expect<SmbTransfers.Failed> { runBlocking { transfers.copy(disk, volume, "A\\f.iso", "B\\f.iso", move = false) } }
        assertTrue(failure.leftover)
        assertTrue(SmbErrors.classify(failure).message.contains(SmbErrors.LEFTOVER_NOTE))
        assertEquals(1, parts().size)
        assertEquals(setOf(volume), transfers.pendingVolumes())
        // Next connection, server reachable again.
        disk.unreachable = false
        val summary = transfers.cleanDeferred(volume, disk)
        assertEquals(1, summary.deleted)
        assertTrue(parts().isEmpty())
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `app tuee pendant l'ecriture nettoyee au demarrage`() = runBlocking {
        disk.folder("B"); disk.file("B\\.f.iso.deadbeef.bonobo-part", "moitié")
        // Receipts as written by a write in progress (« en cours »), then the process died.
        val killed = OperationArtifacts(receipts, "deadbeef-0000-0000-0000-000000000000")
        killed.begin()
        killed.record(OperationArtifacts.Entry(path = "B\\.f.iso.deadbeef.bonobo-part", parent = "B",
            name = ".f.iso.deadbeef.bonobo-part", directory = false, identity = "b\\.f.iso.deadbeef.bonobo-part",
            owned = false, staging = true, volume = volume, operation = killed.id))
        assertEquals(1, transfers.adoptInterrupted())
        assertEquals(setOf(volume), transfers.pendingVolumes())
        transfers.cleanDeferred(volume, disk)
        assertTrue(parts().isEmpty())
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `nettoyage limite au partage concerne`() = runBlocking {
        disk.folder("B"); disk.file("B\\.f.iso.deadbeef.bonobo-part", "moitié")
        val killed = OperationArtifacts(receipts, "deadbeef-0000-0000-0000-000000000000")
        killed.begin()
        killed.record(OperationArtifacts.Entry(path = "B\\.f.iso.deadbeef.bonobo-part", parent = "B",
            name = ".f.iso.deadbeef.bonobo-part", directory = false, identity = "b\\.f.iso.deadbeef.bonobo-part",
            owned = false, staging = true, volume = SmbTransfers.volume(9, "Autre"), operation = killed.id))
        transfers.cleanDeferred(volume, disk)
        assertEquals("le reçu d'un autre serveur n'est pas touché", 1, parts().size)
    }

    // ─────────────────────────────── Enregistrement d'un texte ───────────────────────────────

    private fun local(text: String) = File.createTempFile("edit-", ".txt").apply { writeText(text); deleteOnExit() }

    @Test
    fun `enregistrement remplace l'original apres verification`() = runBlocking {
        disk.folder("Notes"); disk.file("Notes\\todo.txt", "ancien")
        transfers.replace(disk, volume, "Notes\\todo.txt", local("nouveau contenu"))
        assertEquals("nouveau contenu", disk.text("Notes\\todo.txt"))
        assertTrue(parts().isEmpty()); assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `enregistrement interrompu l'original reste intact`() = runBlocking {
        disk.folder("Notes"); disk.file("Notes\\todo.txt", "ancien")
        disk.failWriteAfter = 3
        expect<SmbTransfers.Failed> { runBlocking { transfers.replace(disk, volume, "Notes\\todo.txt", local("nouveau contenu")) } }
        assertEquals("ancien", disk.text("Notes\\todo.txt"))
        assertTrue(parts().isEmpty())
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `enregistrement corrompu l'original reste intact`() = runBlocking {
        disk.folder("Notes"); disk.file("Notes\\todo.txt", "ancien")
        disk.corruptWrites = true
        expect<SmbTransfers.Failed> { runBlocking { transfers.replace(disk, volume, "Notes\\todo.txt", local("nouveau")) } }
        assertEquals("ancien", disk.text("Notes\\todo.txt"))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `enregistrement coupe le serveur partiel retire plus tard original intact`() = runBlocking {
        disk.folder("Notes"); disk.file("Notes\\todo.txt", "ancien")
        disk.failWriteAfter = 2; disk.unreachableAfterWriteFailure = true
        val failure = expect<SmbTransfers.Failed> { runBlocking { transfers.replace(disk, volume, "Notes\\todo.txt", local("nouveau")) } }
        assertTrue(failure.leftover)
        disk.unreachable = false
        assertEquals("ancien", disk.text("Notes\\todo.txt"))
        transfers.cleanDeferred(volume, disk)
        assertTrue(parts().isEmpty())
        assertEquals("ancien", disk.text("Notes\\todo.txt"))
    }

    // ─────────────────────────────── Journal ───────────────────────────────

    @Test
    fun `journal storage SMB sans chemin ni nom`() = runBlocking {
        val dir = File(receipts, "journal")
        val journal = TechnicalJournal(dir)
        Diagnostic.journal = journal
        journal.setEnabled(true)
        disk.folder("Dossier secret"); disk.folder("Cible"); disk.file("Dossier secret\\facture-nico.pdf", "p".repeat(2000))
        disk.failWriteAfter = 700
        expect<SmbTransfers.Failed> { runBlocking { transfers.copy(disk, volume, "Dossier secret\\facture-nico.pdf", "Cible\\facture-nico.pdf", false) } }
        journal.awaitIdle()
        val lines = File(dir, "current.log").readLines()
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue("storage=SMB attendu : $line", line.contains("storage=SMB"))
            assertFalse(line, line.contains("secret", ignoreCase = true) || line.contains("facture") || line.contains("Public"))
        }
        assertTrue(lines.any { it.contains(" CLEANUP DELETED ") })
        assertNull(lines.firstOrNull { it.contains("PARTIAL_LEFTOVER") })
    }
}
