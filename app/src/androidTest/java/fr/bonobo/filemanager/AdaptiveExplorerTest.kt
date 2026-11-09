package fr.bonobo.filemanager

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import fr.bonobo.filemanager.presentation.ui.main.MainViewModel
import fr.bonobo.filemanager.presentation.ui.main.SortMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AdaptiveExplorerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun foldersSelectionAndClipboardSurviveRotationAndRecreation() {
        compose.onNodeWithText("Explorateur").performSemanticsAction(SemanticsActions.OnClick) { it() }
        lateinit var model: MainViewModel
        lateinit var first: File
        lateinit var second: File
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            val root = File(activity.filesDir, "adaptive-explorer-test-${System.nanoTime()}").apply { mkdirs() }
            first = File(root, "first").apply { mkdirs() }
            second = File(root, "second").apply { mkdirs() }
            File(first, "first-long-filename-for-rotation.txt").writeText("candidate test")
            File(second, "second-panel.txt").writeText("candidate test")
            model.openFolder(first.path, resetNavigationRoot = true)
        }
        compose.waitUntil(10_000) { model.uiState.value.files.any { it.name.startsWith("first-long") } }
        compose.runOnIdle {
            model.toggleDualPane()
            model.setActivePanel(2)
            model.loadFiles(second.path)
        }
        compose.waitUntil(10_000) { model.uiState.value.secondFiles.any { it.name == "second-panel.txt" } }
        compose.onNodeWithText("second-panel.txt").assertIsDisplayed()
        compose.runOnIdle {
            model.setClipboard(model.uiState.value.files.first(), false)
            model.toggleSelection(model.uiState.value.secondFiles.first().path)
            model.setSortMode(SortMode.SIZE)
            if (!model.uiState.value.isGridView) model.toggleViewMode()
        }
        val before = model.uiState.value
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        compose.onNodeWithText("first-long-filename-for-rotation.txt").assertIsDisplayed()
        compose.onNodeWithText("second-panel.txt").assertIsDisplayed()
        assertEquals(before.currentPath, model.uiState.value.currentPath)
        assertEquals(before.secondPath, model.uiState.value.secondPath)
        assertEquals(before.selectedPaths, model.uiState.value.selectedPaths)
        assertEquals(before.clipboard, model.uiState.value.clipboard)
        assertEquals(before.isGridView, model.uiState.value.isGridView)
        assertEquals(before.sortMode, model.uiState.value.sortMode)
        assertEquals(before.isSortAscending, model.uiState.value.isSortAscending)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        compose.onNodeWithText("second-panel.txt").assertIsDisplayed()
        compose.onNodeWithText("first-long-filename-for-rotation.txt").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertSame(model, ViewModelProvider(it)[MainViewModel::class.java])
        }
        assertEquals(before.selectedPaths, model.uiState.value.selectedPaths)
        assertEquals(before.clipboard, model.uiState.value.clipboard)
        assertEquals(before.isGridView, model.uiState.value.isGridView)
        assertEquals(before.sortMode, model.uiState.value.sortMode)
        assertEquals(before.isSortAscending, model.uiState.value.isSortAscending)
        compose.onNodeWithText("⇄").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("first-long-filename-for-rotation.txt").assertIsDisplayed()
        assertEquals(first.path, model.uiState.value.currentPath)
        assertEquals(second.path, model.uiState.value.secondPath)
        compose.onNodeWithContentDescription("Options").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Copier vers l’autre panneau").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val copied = File(second, "first-long-filename-for-rotation.txt")
        compose.waitUntil(10_000) {
            copied.exists() && model.uiState.value.clipboard == null && !model.uiState.value.isSecondLoading
        }
        assertEquals("candidate test", copied.readText())
        assertEquals("candidate test", File(first, copied.name).readText())
        assertEquals(2, model.uiState.value.activePanel)
        assertTrue(model.uiState.value.secondFiles.any { it.path == copied.path })
    }
    @Test fun multipleSelectionCopiesFilesAndFoldersViaToolbar() {
        compose.onNodeWithText("Explorateur").performSemanticsAction(SemanticsActions.OnClick) { it() }
        lateinit var model: MainViewModel
        lateinit var base: File
        lateinit var source: File
        lateinit var destination: File
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            base = File(activity.cacheDir, "selection-${System.nanoTime()}").apply { mkdirs() }
            source = File(base, "source").apply { mkdir() }
            destination = File(base, "destination").apply { mkdir() }
            File(source, "file.txt").writeText("file")
            File(source, "folder").mkdir(); File(source, "folder/child").writeText("child")
            model.openFolder(source.path, resetNavigationRoot = true)
        }
        try {
            compose.waitUntil(10000) { model.uiState.value.files.size == 2 }
            compose.runOnIdle { model.uiState.value.files.forEach { model.toggleSelection(it.path) } }
            compose.onNodeWithContentDescription("Copier la sélection").performClick()
            assertEquals(2, model.uiState.value.clipboard!!.files.size)
            assertTrue(model.uiState.value.selectedPaths.isEmpty())
            compose.runOnIdle { model.openFolder(destination.path, resetNavigationRoot = true) }
            compose.waitUntil(10000) { model.uiState.value.currentPath == destination.path && !model.uiState.value.isLoading }
            compose.runOnIdle { model.pasteClipboard() }
            compose.waitUntil(15000) { model.uiState.value.clipboard == null && model.uiState.value.files.size == 2 }
            assertEquals("file", File(destination, "file.txt").readText())
            assertEquals("child", File(destination, "folder/child").readText())
            assertTrue(File(source, "file.txt").exists())
            assertTrue(File(source, "folder/child").exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun returningToForegroundRefreshesOpenFolder() {
        compose.onNodeWithText("Explorateur").performSemanticsAction(SemanticsActions.OnClick) { it() }
        lateinit var model: MainViewModel
        lateinit var folder: File
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            folder = File(activity.cacheDir, "foreground-${System.nanoTime()}").apply { mkdirs() }
            File(folder, "before.txt").writeText("before")
            model.openFolder(folder.path, resetNavigationRoot = true)
        }
        compose.waitUntil(10000) { model.uiState.value.files.any { it.name == "before.txt" } }
        try {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val fixture = instrumentation.context
            fixture.startActivity(android.content.Intent().setClassName(fixture.packageName,
                "fr.bonobo.filemanager.saf.TestBackgroundActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (compose.activityRule.scenario.state != androidx.lifecycle.Lifecycle.State.CREATED) {
                check(android.os.SystemClock.uptimeMillis() < deadline)
                android.os.SystemClock.sleep(20)
            }
            File(folder, "before.txt").delete()
            File(folder, "after.txt").writeText("external change")
            fixture.startActivity(android.content.Intent().setClassName(fixture.packageName,
                "fr.bonobo.filemanager.saf.TestBackgroundActivity").putExtra("return", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            val returnDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (compose.activityRule.scenario.state != androidx.lifecycle.Lifecycle.State.RESUMED) {
                check(android.os.SystemClock.uptimeMillis() < returnDeadline)
                android.os.SystemClock.sleep(20)
            }
            compose.waitUntil(10000) { model.uiState.value.files.any { it.name == "after.txt" } }
            assertFalse(model.uiState.value.files.any { it.name == "before.txt" })
        } finally { folder.deleteRecursively() }
    }

    @Test fun downloadsHomeTileReplacesPreviousFolderAndRemovesVirtualCategory() {
        lateinit var model: MainViewModel
        lateinit var previous: File
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            previous = File(activity.cacheDir, "previous-${System.nanoTime()}").apply { mkdirs() }
            model.openFolder(previous.path, resetNavigationRoot = true)
            model.loadCategory("Images")
        }
        try {
            compose.onNodeWithText("Téléchargements").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { model.uiState.value.categoryName == null &&
                model.navigationPath() == android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath }
            assertNotEquals(previous.path, model.navigationPath())
            assertEquals(1, model.uiState.value.activePanel)
        } finally { previous.deleteRecursively() }
    }

    @Test fun virtualCategoryStillRejectsPasteBeforeStartingTransfer() {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity { activity -> model = ViewModelProvider(activity)[MainViewModel::class.java] }
        compose.runOnIdle {
            model.loadCategory("Images")
            model.setClipboard(fr.bonobo.filemanager.domain.model.FileItem("/isolated/test.txt", "test.txt", 1,
                java.util.Date(0), false), false)
            val before = model.transfers.value.id
            model.pasteClipboard()
            assertEquals("Ouvrez un dossier pour coller les éléments.", model.uiState.value.error)
            assertEquals(before, model.transfers.value.id)
            assertFalse(model.transfers.value.active)
        }
    }

}
