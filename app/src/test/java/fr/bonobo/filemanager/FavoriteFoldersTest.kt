package fr.bonobo.filemanager

import fr.bonobo.filemanager.domain.model.FavoriteFolders
import fr.bonobo.filemanager.domain.model.FileItem
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class FavoriteFoldersTest {
    private fun item(name: String, directory: Boolean, favorite: Boolean) = FileItem(
        path = "/storage/$name", name = name, size = 0, lastModified = Date(0),
        isDirectory = directory, isFavorite = favorite, mimeType = null)

    @Test fun onlyStarredFoldersAppear() {
        val selected = item("Photos", true, true)
        assertEquals(listOf(selected), FavoriteFolders.select(listOf(
            item("Downloads", true, false), selected, item("facture.pdf", false, true),
            item("Musique", true, false))))
    }
    @Test fun searchStaysInsideFavoriteFolders() {
        val selected = item("Photos famille", true, true)
        val items = listOf(selected, item("Photos voyage", true, false), item("Photos.pdf", false, true))
        assertEquals(listOf(selected), FavoriteFolders.select(items, "  PHOTOS  "))
        assertTrue(FavoriteFolders.select(items, "voyage").isEmpty())
    }
    @Test fun removedFavoriteAndDuplicates() {
        val selected = item("Photos", true, true)
        assertEquals(listOf(selected), FavoriteFolders.select(listOf(selected, selected)))
        assertTrue(FavoriteFolders.select(listOf(selected.copy(isFavorite = false))).isEmpty())
    }
}
