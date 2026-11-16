package fr.bonobo.filemanager.domain.model

object FavoriteFolders {
    fun select(items: List<FileItem>, query: String = ""): List<FileItem> = items
        .filter { it.isDirectory && it.isFavorite && it.name.contains(query.trim(), ignoreCase = true) }
        .distinctBy { it.path }
}
