package fr.bonobo.filemanager

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.bonobo.filemanager.domain.transfer.*
import fr.bonobo.filemanager.presentation.components.TransferPanel
import fr.bonobo.filemanager.presentation.ui.theme.*
import org.junit.Rule
import org.junit.Test

class TransferPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun panelKeepsThemeColorsAndShowsCancellationAsPending() {
        val mode = mutableStateOf(AppThemeMode.LIGHT)
        val state = mutableStateOf(TransferState(id = "isolated", active = true,
            phase = TransferPhase.COPYING, currentName = "test.txt", currentSource = "/test/source",
            destination = "/test/target", totalRoots = 2, totalFiles = 2, totalBytes = 100, bytes = 50))
        compose.setContent {
            BonoboFileManagerTheme(themeMode = mode.value, dynamicColor = false) {
                TransferPanel(state.value, cancel = {
                    state.value = state.value.copy(phase = TransferPhase.CANCEL_REQUESTED, cancelRequested = true)
                }, dismiss = {})
            }
        }
        for (theme in AppThemeMode.entries) {
            compose.runOnIdle { mode.value = theme }
            compose.onNodeWithText("test.txt").assertIsDisplayed()
            compose.onNodeWithText("50 % copiés · vérification non comprise").assertExists()
        }
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Annulation demandée").assertExists()
        compose.onNodeWithText("Annuler").assertIsNotEnabled()
        compose.onNodeWithText("Détails").performClick()
        compose.onNodeWithText("Copie et déplacement").assertExists()
        compose.onNodeWithText("Pas de reprise automatique après fermeture de l’application.").assertExists()
    }
    @Test fun acknowledgedCompletionDoesNotReturnOnRecomposition() {
        val state = mutableStateOf(TransferState(id = "completed", completedFiles = 1))
        compose.setContent {
            TransferPanel(state.value, cancel = {}, dismiss = { state.value = state.value.copy(acknowledged = true) })
        }
        compose.onNodeWithText("Détails").performClick()
        compose.onNodeWithText("Copie et déplacement").assertExists()
        compose.onNodeWithText("Fermer les détails").performClick()
        compose.onNodeWithText("Terminé").assertDoesNotExist()
        repeat(3) {
            compose.runOnIdle { state.value = state.value.copy(bytes = state.value.bytes + 1) }
            compose.onNodeWithText("Terminé").assertDoesNotExist()
        }
    }

    @Test fun newTransferAfterCancellationShowsOnlyItsOwnProgress() {
        val state = mutableStateOf(TransferState(id = "old", active = true, phase = TransferPhase.COPYING,
            currentName = "old-file", totalBytes = 100, bytes = 40, totalFiles = 1, totalRoots = 1))
        compose.setContent { TransferPanel(state.value, cancel = {}, dismiss = {}) }
        compose.onNodeWithText("40 % copiés · vérification non comprise").assertExists()
        compose.runOnIdle { state.value = state.value.copy(active = false, phase = TransferPhase.CANCELLED, cancelRequested = true) }
        compose.onNodeWithText("Annulé · opération arrêtée").assertExists()
        compose.runOnIdle { state.value = TransferState(id = "new", active = true, currentName = "new-file", phase = TransferPhase.PREPARING) }
        compose.onNodeWithText("Préparation").assertExists()
        compose.onNodeWithText("old-file").assertDoesNotExist()
        compose.onNodeWithText("40 % copiés · vérification non comprise").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = TransferPhase.COPYING, totalBytes = 100, totalFiles = 1) }
        compose.onNodeWithText("0 % copiés · vérification non comprise").assertExists()
        compose.runOnIdle { state.value = state.value.copy(bytes = 7) }
        compose.onNodeWithText("7 % copiés · vérification non comprise").assertExists()
        compose.onNodeWithText("40 % copiés · vérification non comprise").assertDoesNotExist()
    }

}
