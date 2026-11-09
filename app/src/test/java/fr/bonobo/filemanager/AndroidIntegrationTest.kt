package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.IncomingFiles
import fr.bonobo.filemanager.util.SafFiles
import org.junit.Assert.*
import org.junit.Test

class AndroidIntegrationTest {
    @Test fun receivedNamesCannotEscapePrivateDirectory() {
        assertEquals("secret.pdf", IncomingFiles.safeName("../../secret.pdf"))
        assertEquals("secret.pdf", IncomingFiles.safeName("C:\\temp\\secret.pdf"))
        assertEquals("Document", IncomingFiles.safeName(".."))
        assertEquals("ab", IncomingFiles.safeName("a\u0000b"))
        assertEquals(120, IncomingFiles.safeName("a".repeat(200)).length)
    }
    @Test fun safNamesRejectTraversalAndControlCharacters() {
        listOf("", ".", "..", "../file", "folder/file", "folder\\file", "a\u0000b", "a\nb").forEach { name ->
            assertTrue(runCatching { SafFiles.validateName(name) }.isFailure)
        }
        SafFiles.validateName("Photos été 2026")
    }
    @Test fun onlyImplementedFormatsAreAdvertised() {
        assertFalse(IncomingFiles.extensions.containsKey("application/msword"))
        assertFalse(IncomingFiles.extensions.containsKey("application/octet-stream"))
        assertFalse(IncomingFiles.extensions.containsKey("*/*"))
        assertEquals("pdf", IncomingFiles.extensions["application/pdf"])
    }
    @Test fun streamLimitsAndWriteErrorsAreNotSilentlyAccepted() {
        val output = java.io.ByteArrayOutputStream()
        assertEquals(3L, IncomingFiles.copyBounded("USB".byteInputStream(), output, 3))
        assertEquals("USB", output.toString("UTF-8"))
        assertTrue(runCatching { IncomingFiles.copyBounded("1234".byteInputStream(), java.io.ByteArrayOutputStream(), 3) }.isFailure)
        val failedOutput = object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("Support retiré") }
        }
        assertEquals("Support retiré", runCatching { IncomingFiles.copyBounded("USB".byteInputStream(), failedOutput) }.exceptionOrNull()?.message)
    }
    @Test fun persistenceUsesOnlyPermissionsActuallyReturnedByAndroid() {
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persist = android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(read, IncomingFiles.grantedAccess(read or persist))
        assertEquals(read or write, IncomingFiles.grantedAccess(read or write or persist))
        assertTrue(IncomingFiles.canPersist(read or persist))
        assertFalse(IncomingFiles.canPersist(read))
        assertFalse(IncomingFiles.canPersist(persist))
    }
}
