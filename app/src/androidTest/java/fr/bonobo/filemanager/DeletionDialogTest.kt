package fr.bonobo.filemanager

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.presentation.ui.operations.DeleteDialog
import fr.bonobo.filemanager.presentation.ui.operations.MultiDeleteDialog
import org.junit.Rule
import org.junit.Test
import java.util.Date

class DeletionDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun trashOnlyAppearsForLocalFilesAndFolders() {
        val item = mutableStateOf(FileItem("/fixture/file", "fixture", 0, Date(0), false))
        compose.setContent {
            DeleteDialog(item.value, false, {}, {}, {})
        }
        for (directory in listOf(false, true)) {
            compose.runOnIdle { item.value = item.value.copy(path = "/fixture/local", isDirectory = directory) }
            compose.onNodeWithText("Mettre à la corbeille").assertExists()
            compose.runOnIdle { item.value = item.value.copy(path = "content://opaque/document/test") }
            compose.onNodeWithText("Mettre à la corbeille").assertDoesNotExist()
            compose.onNodeWithText("Suppression directe").assertExists()
        }
    }
    @Test fun mixedSelectionNeverOffersTrashOrDeletesWithoutClick() {
        var calls = 0
        compose.setContent {
            MultiDeleteDialog(count = 2, canTrash = false, isTrashView = false,
                onDismiss = {}, onConfirmPermanent = { calls++ }, onMoveToTrash = { error("No trash") })
        }
        compose.onNodeWithText("Mettre à la corbeille").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, calls) }
    }
}
