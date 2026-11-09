package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbClientRepository
import fr.bonobo.filemanager.domain.model.ConnectionType
import fr.bonobo.filemanager.domain.model.RemoteConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbShareParsingTest {

    private fun smb(share: String?) = RemoteConnection(
        name = "PC", host = "192.168.8.104", port = 445, type = ConnectionType.SMB, share = share
    )

    @Test
    fun `partage seul ouvre la racine`() {
        assertEquals("c", SmbClientRepository.shareName(smb("c")))
        assertEquals("/", SmbClientRepository.startPath(smb("c")))
    }

    @Test
    fun `partage avec dossier de depart`() {
        val connection = smb("c/Users/Nico/Desktop")
        assertEquals("c", SmbClientRepository.shareName(connection))
        assertEquals("/Users/Nico/Desktop", SmbClientRepository.startPath(connection))
    }

    @Test
    fun `separateurs windows et barres superflues acceptes`() {
        val connection = smb("\\c\\Users\\Nico\\")
        assertEquals("c", SmbClientRepository.shareName(connection))
        assertEquals("/Users/Nico", SmbClientRepository.startPath(connection))
    }

    @Test
    fun `sans partage`() {
        assertNull(SmbClientRepository.shareName(smb(null)))
        assertNull(SmbClientRepository.shareName(smb("  ")))
        assertEquals("/", SmbClientRepository.startPath(smb(null)))
    }
}
