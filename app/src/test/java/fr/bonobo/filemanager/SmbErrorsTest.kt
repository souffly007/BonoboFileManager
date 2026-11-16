package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbErrors
import fr.bonobo.filemanager.data.repository.SmbErrors.Kind
import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException

class SmbErrorsTest {

    @Test
    fun `codes NTSTATUS classes`() {
        assertEquals(Kind.ACCESS_DENIED, SmbErrors.kindForStatus(0xC0000022L))
        assertEquals(Kind.NOT_FOUND, SmbErrors.kindForStatus(0xC0000034L))
        assertEquals(Kind.NOT_FOUND, SmbErrors.kindForStatus(0xC000003AL))
        assertEquals(Kind.SHARE_NOT_FOUND, SmbErrors.kindForStatus(0xC00000CCL))
        assertEquals(Kind.AUTH_REFUSED, SmbErrors.kindForStatus(0xC000006DL))
        assertEquals(Kind.AUTH_REFUSED, SmbErrors.kindForStatus(0xC000006AL))
        assertEquals(Kind.UNREACHABLE, SmbErrors.kindForStatus(0xC00000BEL))
        assertEquals(Kind.OTHER, SmbErrors.kindForStatus(0xC0000043L))
    }

    @Test
    fun `message court et code brut seulement dans le detail`() {
        val c = SmbErrors.fromStatus(0xC0000022L, "STATUS_ACCESS_DENIED")
        assertEquals("Accès refusé par le serveur SMB.", c.message)
        assertFalse(c.message.contains("0x"))
        assertEquals("STATUS_ACCESS_DENIED (0xC0000022)", c.technical)
        assertEquals(TechnicalJournal.Result.PERMISSION_DENIED, c.journalResult)
        val failure = c.toThrowable(RuntimeException())
        assertEquals(c.message, SmbErrors.userMessage(failure))
        assertEquals(c.technical, SmbErrors.technicalDetail(failure))
    }

    @Test
    fun `serveur injoignable`() {
        val c = SmbErrors.classify(RuntimeException("transport", ConnectException("Connection refused")))
        assertEquals(Kind.UNREACHABLE, c.kind)
        assertEquals(TechnicalJournal.Result.UNREACHABLE, c.journalResult)
        assertEquals(Kind.UNREACHABLE, SmbErrors.classify(UnknownHostException("nas")).kind)
    }

    @Test
    fun `nos propres refus gardent leur message`() {
        val c = SmbErrors.classify(IllegalArgumentException("La source et la destination sont identiques"))
        assertEquals(Kind.REFUSED, c.kind)
        assertEquals("La source et la destination sont identiques", c.message)
        assertNull(c.technical)
    }

    @Test
    fun `erreur inconnue message generique et detail technique`() {
        val c = SmbErrors.classify(java.io.IOException("boom"))
        assertEquals(Kind.OTHER, c.kind)
        assertEquals("Le serveur SMB a refusé l’opération.", c.message)
        assertEquals("IOException : boom", c.technical)
    }

    @Test
    fun `echec deja classe conserve`() {
        val inner = TransferFailure(fr.bonobo.filemanager.domain.transfer.FailureKind.OTHER, "Accès refusé par le serveur SMB.", "STATUS_ACCESS_DENIED (0xC0000022)")
        val c = SmbErrors.classify(inner)
        assertEquals("Accès refusé par le serveur SMB.", c.message)
        assertEquals("STATUS_ACCESS_DENIED (0xC0000022)", c.technical)
    }

    @Test
    fun `ligne de journal SMB sans donnee utilisateur`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "smb-journal-" + System.nanoTime())
        val journal = TechnicalJournal(dir)
        Diagnostic.journal = journal
        try {
            journal.setEnabled(true)
            val op = Diagnostic.operation()
            Diagnostic.smb(TechnicalJournal.SmbOp.LIST, TechnicalJournal.Result.START, op, TechnicalJournal.Kind.DIRECTORY)
            Diagnostic.smb(TechnicalJournal.SmbOp.LIST, TechnicalJournal.Result.PERMISSION_DENIED, op,
                TechnicalJournal.Kind.DIRECTORY, durationMs = 42, ntStatus = 0xC0000022L)
            journal.awaitIdle()
            val lines = File(dir, "current.log").readLines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].contains("SMB_OPERATION START op=$op"))
            assertTrue(lines[0].contains("storage=SMB kind=DIRECTORY smbOp=LIST ntStatus=-"))
            assertTrue(lines[1].contains("SMB_OPERATION PERMISSION_DENIED op=$op"))
            assertTrue(lines[1].contains("durationMs=42"))
            assertTrue(lines[1].contains("ntStatus=0xC0000022"))
            // Closed vocabulary: ASCII words, numbers, the op UUID — no room for a host, path, user or password.
            lines.forEach { line -> assertTrue(line, Regex("^[A-Za-z0-9_=. :-]+$").matches(line)) }
        } finally {
            Diagnostic.journal = null
            dir.deleteRecursively()
        }
    }
}
