package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.local.RemoteBookmarkStore
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBookmarkPathTest {
    @Test fun samePathWithOrWithoutSlashesIsOneFavorite() {
        assertEquals("/Films/2024", RemoteBookmarkStore.normalize("/Films/2024/"))
        assertEquals("/Films/2024", RemoteBookmarkStore.normalize("Films/2024"))
        assertEquals("/", RemoteBookmarkStore.normalize(""))
        assertEquals("/", RemoteBookmarkStore.normalize("/"))
    }

    @Test fun displayNameIsLastFolderOrConnectionForRoot() {
        assertEquals("2024", RemoteBookmarkStore.displayNameOf("/Films/2024/", "NAS"))
        assertEquals("NAS", RemoteBookmarkStore.displayNameOf("/", "NAS"))
    }
}
