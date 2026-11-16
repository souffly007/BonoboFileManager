package fr.bonobo.filemanager.presentation.ui.dashboard

/** Only the built-in Downloads tile is a concrete folder; saved bookmarks keep their own path. */
internal object HomeFolderShortcut {
    fun downloadsPath(title: String, bookmarkPath: String?, downloads: String): String? =
        downloads.takeIf { title == "Téléchargements" && bookmarkPath == null }
}
