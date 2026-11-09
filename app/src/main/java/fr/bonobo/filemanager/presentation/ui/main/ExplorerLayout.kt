package fr.bonobo.filemanager.presentation.ui.main

// Two panes need at least 300 dp each for filenames and 48 dp touch targets.
// Use the available content width, including in split-screen windows.
internal fun showExplorerPanesSideBySide(availableWidthDp: Float): Boolean = availableWidthDp >= 600f
