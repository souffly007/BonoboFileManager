package fr.bonobo.filemanager

import fr.bonobo.filemanager.util.UsbAccessScope
import org.junit.Assert.*
import org.junit.Test

class UsbAccessScopeTest {
    @Test fun rootAndFolderGrantsOnSameVolumeRemainDistinct() {
        val uuid = "ABCD-1234"
        assertEquals(UsbAccessScope.Scope.VOLUME_ROOT, UsbAccessScope.classify("ABCD-1234:", uuid))
        assertEquals(UsbAccessScope.Scope.FOLDER, UsbAccessScope.classify("ABCD-1234:Photos/2026", uuid))
        assertEquals(UsbAccessScope.Scope.UNKNOWN, UsbAccessScope.classify("other:Photos", uuid))
        assertEquals("Clé USB : racine autorisée", UsbAccessScope.label(UsbAccessScope.Scope.VOLUME_ROOT))
        assertEquals("Dossier autorisé sur la clé USB", UsbAccessScope.label(UsbAccessScope.Scope.FOLDER))
    }

    @Test fun missingVolumeIdentityNeverTurnsGrantIntoVolumeRoot() {
        assertEquals(UsbAccessScope.Scope.UNKNOWN, UsbAccessScope.classify("ABCD-1234:", null))
        assertEquals(UsbAccessScope.Scope.UNKNOWN, UsbAccessScope.classify("ABCD-1234:", ""))
    }

    @Test fun deniedRootSelectionExplainsFolderAlternative() {
        assertTrue(UsbAccessScope.ROOT_SELECTION_REJECTED.contains("Android"))
        assertTrue(UsbAccessScope.ROOT_SELECTION_REJECTED.contains("dossier"))
    }
}
