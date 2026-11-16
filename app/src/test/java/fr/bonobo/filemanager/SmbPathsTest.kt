package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbPaths
import fr.bonobo.filemanager.data.repository.SmbPaths.ShareInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SmbPathsTest {

    private fun valid(raw: String?): ShareInput.Valid {
        val parsed = SmbPaths.parseShare(raw)
        assertTrue("« $raw » devrait être accepté : $parsed", parsed is ShareInput.Valid)
        return parsed as ShareInput.Valid
    }

    private fun invalid(raw: String): String {
        val parsed = SmbPaths.parseShare(raw)
        assertTrue("« $raw » devrait être refusé : $parsed", parsed is ShareInput.Invalid)
        return (parsed as ShareInput.Invalid).message
    }

    // --- Champ « Partage » -----------------------------------------------------------------------

    @Test
    fun `partage seul`() {
        val p = valid("Documents")
        assertEquals("Documents", p.share); assertEquals("", p.base); assertNull(p.host)
        assertEquals("Documents", p.normalized)
    }

    @Test
    fun `partage avec dossier de depart`() {
        val p = valid("c/Users/Nico/Desktop")
        assertEquals("c", p.share); assertEquals("Users/Nico/Desktop", p.base)
    }

    @Test
    fun `un seul antislash initial n'est pas un hote`() {
        val p = valid("\\c\\Users\\Nico\\")
        assertEquals("c", p.share); assertEquals("Users/Nico", p.base); assertNull(p.host)
    }

    @Test
    fun `adresse smb complete`() {
        val p = valid("smb://192.168.1.10/Documents/Photos/2024")
        assertEquals("192.168.1.10", p.host)
        assertEquals("Documents", p.share); assertEquals("Photos/2024", p.base)
        assertEquals("Documents/Photos/2024", p.normalized)
    }

    @Test
    fun `adresse smb majuscules barre finale et pourcent`() {
        val p = valid("SMB://nas/Mes%20Documents/Dossier%20A/")
        assertEquals("nas", p.host); assertEquals("Mes Documents", p.share); assertEquals("Dossier A", p.base)
    }

    @Test
    fun `chemin UNC`() {
        val p = valid("\\\\nas\\Public\\Films")
        assertEquals("nas", p.host); assertEquals("Public", p.share); assertEquals("Films", p.base)
        val q = valid("//nas/Public")
        assertEquals("nas", q.host); assertEquals("Public", q.share); assertEquals("", q.base)
    }

    @Test
    fun `espaces conserves dans les noms et retires autour`() {
        val p = valid("  Mes Documents / Vacances d'été  ")
        assertEquals("Mes Documents", p.share); assertEquals("Vacances d'été", p.base)
    }

    @Test
    fun `vide ou absent = pas de partage`() {
        assertNull(valid(null).share); assertNull(valid("   ").share); assertNull(valid("   ").normalized)
    }

    @Test
    fun `saisies refusees avec message`() {
        assertTrue(invalid("smb://nas").contains("partage", ignoreCase = true))
        assertTrue(invalid("smb://").isNotBlank())
        assertTrue(invalid("smb:nas/Public").contains("smb://"))
        assertTrue(invalid("\\\\nas").contains("partage", ignoreCase = true))
        assertTrue(invalid("http://nas/Public").contains("smb://"))
        assertTrue(invalid("C:\\Users\\Nico").contains("lettre de lecteur"))
        assertTrue(invalid("Public/../Secret").contains(".."))
        assertTrue(invalid("Public/a*b").isNotBlank())
        assertTrue(invalid("smb://nas/Public%2").isNotBlank())
    }

    @Test
    fun `configured leve une erreur explicite`() {
        try { SmbPaths.configured("http://x/y"); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("smb://"))
        }
    }

    // --- Chemins virtuels → partage + chemin relatif ---------------------------------------------

    private val documents = valid("Documents")
    private val desktop = valid("c/Users/Nico/Desktop")
    private val none = valid(null)

    @Test
    fun `racine`() {
        assertEquals(SmbPaths.Location("Documents", ""), SmbPaths.locate(documents, "/"))
        assertEquals(SmbPaths.Location("c", "Users\\Nico\\Desktop"), SmbPaths.locate(desktop, "/"))
    }

    @Test
    fun `sous-dossier`() {
        assertEquals(SmbPaths.Location("Documents", "Photos\\2024"), SmbPaths.locate(documents, "/Photos/2024"))
        assertEquals(SmbPaths.Location("c", "Users\\Nico\\Desktop\\Photos"), SmbPaths.locate(desktop, "/Photos"))
    }

    @Test
    fun `barre finale et barres doublees ignorees`() {
        assertEquals("Photos\\2024", SmbPaths.relativePath(documents, "/Photos/2024/"))
        assertEquals("Photos\\2024", SmbPaths.relativePath(documents, "//Photos//2024"))
    }

    @Test
    fun `parent vide`() {
        assertEquals(SmbPaths.Location("Documents", ""), SmbPaths.locate(documents, ""))
        assertEquals("Users\\Nico\\Desktop", SmbPaths.relativePath(desktop, ""))
    }

    @Test
    fun `espaces dans les chemins`() {
        assertEquals("Mes photos\\Été 2024", SmbPaths.relativePath(documents, "/Mes photos/Été 2024"))
    }

    @Test
    fun `sans partage configure le premier segment est le partage`() {
        assertEquals(SmbPaths.Location("Public", "Films"), SmbPaths.locate(none, "/Public/Films"))
        assertEquals(SmbPaths.Location("Public", ""), SmbPaths.locate(none, "/Public/"))
        try { SmbPaths.locate(none, "/"); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("partage"))
        }
    }

    @Test
    fun `adresse smb et UNC donnent le meme chemin que la forme courte`() {
        val fromUrl = valid("smb://nas/c/Users/Nico/Desktop")
        val fromUnc = valid("\\\\nas\\c\\Users\\Nico\\Desktop")
        assertEquals(SmbPaths.locate(desktop, "/Photos"), SmbPaths.locate(fromUrl, "/Photos"))
        assertEquals(SmbPaths.locate(desktop, "/Photos"), SmbPaths.locate(fromUnc, "/Photos"))
    }

    @Test
    fun `chemin virtuel d'un enfant liste`() {
        assertEquals("/a.txt", SmbPaths.childVirtualPath("/", "a.txt"))
        assertEquals("/a.txt", SmbPaths.childVirtualPath("", "a.txt"))
        assertEquals("/x/a.txt", SmbPaths.childVirtualPath("/x", "a.txt"))
        assertEquals("/x/a.txt", SmbPaths.childVirtualPath("/x/", "a.txt"))
    }

    @Test
    fun `jointure`() {
        assertEquals("a.txt", SmbPaths.join("", "a.txt"))
        assertEquals("Photos\\a.txt", SmbPaths.join("Photos", "a.txt"))
        assertEquals("Photos\\a.txt", SmbPaths.join("Photos\\", "a.txt"))
        assertEquals("Photos\\a.txt", SmbPaths.join("Photos/", "a.txt"))
    }

    // --- Copier / Déplacer / Dupliquer -----------------------------------------------------------

    private fun existing(vararg paths: String): (String) -> Boolean {
        val set = paths.map { SmbPaths.identity(it) }.toSet()
        return { SmbPaths.identity(it) in set }
    }

    @Test
    fun `dupliquer dans le meme dossier donne nom (1) ext`() {
        val exists = existing("Photos\\a.txt")
        assertEquals("Photos\\a (1).txt", SmbPaths.planTarget("Photos\\a.txt", "Photos", duplicate = true, exists = exists))
    }

    @Test
    fun `dupliquer choisit le premier nom libre`() {
        val exists = existing("a.txt", "a (1).txt", "a (2).txt")
        assertEquals("a (3).txt", SmbPaths.planTarget("a.txt", "", duplicate = true, exists = exists))
    }

    @Test
    fun `dupliquer un dossier`() {
        val exists = existing("Projets\\Site")
        assertEquals("Projets\\Site (1)", SmbPaths.planTarget("Projets\\Site", "Projets", duplicate = true, exists = exists))
    }

    @Test
    fun `dupliquer ailleurs garde le nom s'il est libre`() {
        assertEquals("Archives\\a.txt", SmbPaths.planTarget("Photos\\a.txt", "Archives", duplicate = true, exists = existing("Photos\\a.txt")))
    }

    @Test
    fun `copier vers un autre dossier`() {
        assertEquals("Archives\\a.txt", SmbPaths.planTarget("Photos\\a.txt", "Archives", duplicate = false, exists = existing("Photos\\a.txt")))
    }

    @Test
    fun `copier dans le meme dossier est refuse`() {
        try { SmbPaths.planTarget("Photos\\a.txt", "photos", duplicate = false, exists = existing("Photos\\a.txt")); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("identiques")) }
    }

    @Test
    fun `copier sur un element existant est refuse`() {
        try { SmbPaths.planTarget("Photos\\a.txt", "Archives", duplicate = false, exists = existing("Photos\\a.txt", "Archives\\A.TXT")); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("existe déjà")) }
    }

    @Test
    fun `dossier dans lui-meme refuse`() {
        try { SmbPaths.planTarget("Projets", "Projets\\Sous", duplicate = false, exists = existing("Projets")); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("lui-même")) }
    }

    @Test
    fun `source racine refusee`() {
        try { SmbPaths.planTarget("", "Archives", duplicate = false, exists = existing()); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("source")) }
    }
}
