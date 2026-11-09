package fr.bonobo.filemanager

import fr.bonobo.filemanager.presentation.ui.main.showExplorerPanesSideBySide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerLayoutTest {
    @Test fun phoneRotationUsesAvailableWidth() {
        assertFalse(showExplorerPanesSideBySide(375f))
        assertTrue(showExplorerPanesSideBySide(780f))
    }

    @Test fun narrowLandscapeKeepsOneReadablePane() {
        assertFalse(showExplorerPanesSideBySide(580f))
    }

    @Test fun tabletAndResizedWindowRespectMinimumPaneWidth() {
        assertTrue(showExplorerPanesSideBySide(800f))
        assertTrue(showExplorerPanesSideBySide(600f))
        assertFalse(showExplorerPanesSideBySide(599f))
        assertFalse(showExplorerPanesSideBySide(400f))
    }
}
