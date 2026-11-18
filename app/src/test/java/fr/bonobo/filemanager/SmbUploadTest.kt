package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbPaths
import fr.bonobo.filemanager.data.repository.SmbTransfers
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.domain.transfer.ConflictAction
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.domain.transfer.TransferSkipped
import fr.bonobo.filemanager.presentation.ui.network.conflictsWith
import fr.bonobo.filemanager.util.IntegrityVerifier
import fr.bonobo.filemanager.util.OperationArtifacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class SmbUploadTest {

    /** Phone side in memory: path → content (file) or null (folder). Paths use « / » like the internal storage. */
    private class Phone : SmbTransfers.UploadSource {
        val nodes = linkedMapOf<String, String?>()
        fun folder(path: String) { nodes[path] = null }
        fun file(path: String, text: String) { nodes[path] = text }
        override fun children(path: String): List<IntegrityVerifier.Child>? {
            check(path in nodes) { "Absent : $path" }
            if (nodes[path] != null) return null
            return nodes.keys.filter { it.substringBeforeLast('/') == path && it != path }
                .map { IntegrityVerifier.Child(it.substringAfterLast('/'), it) }
        }
        override fun open(path: String) = ByteArrayInputStream(nodes[path]!!.toByteArray())
        override fun size(path: String) = nodes[path]!!.toByteArray().size.toLong()
    }

    private lateinit var receipts: File
    private lateinit var transfers: SmbTransfers
    private val disk = InMemorySmbDisk()
    private val phone = Phone()
    private val volume = SmbTransfers.volume(3, "Public")

    @Before fun setUp() {
        receipts = File(System.getProperty("java.io.tmpdir"), "smb-upload-" + System.nanoTime()).apply { mkdirs() }
        transfers = SmbTransfers(receipts)
    }

    @After fun tearDown() { receipts.deleteRecursively() }

    private fun parts() = disk.names().filter { it.endsWith(OperationArtifacts.PART_SUFFIX) }
    private fun receiptFiles() = receipts.listFiles { f -> f.extension == "properties" }.orEmpty().toList()

    private fun upload(path: String, into: String, conflict: ConflictAction = ConflictAction.ASK, hash: Boolean = false, moving: Boolean = false) =
        runBlocking { transfers.upload(disk, volume, phone, path, path.substringAfterLast('/'), into, conflict, hash, moving) }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (e: Throwable) { if (e is T) return e; throw e }
        fail("${T::class.simpleName} attendu"); throw IllegalStateException()
    }

    // ─────────────────────────────── Chemins d'envoi ───────────────────────────────

    @Test
    fun `envoi d'un fichier dans un sous-dossier du partage`() {
        disk.folder("Photos"); disk.folder("Photos\\2024")
        phone.folder("/sdcard/DCIM"); phone.file("/sdcard/DCIM/IMG_1.jpg", "pixels")
        val result = upload("/sdcard/DCIM/IMG_1.jpg", "Photos\\2024")
        assertEquals("Photos\\2024\\IMG_1.jpg", result.target)
        assertEquals("pixels", disk.text("Photos\\2024\\IMG_1.jpg"))
        assertEquals(setOf("/sdcard/DCIM/IMG_1.jpg"), result.hashes.keys)
        assertTrue(parts().isEmpty()); assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `envoi a la racine du partage`() {
        phone.folder("/sdcard"); phone.file("/sdcard/note.txt", "texte")
        assertEquals("note.txt", upload("/sdcard/note.txt", "").target)
        assertEquals("texte", disk.text("note.txt"))
    }

    @Test
    fun `envoi d'un dossier complet depuis une cle USB`() {
        disk.folder("Sauvegardes")
        phone.folder("/storage/1A2B-3C4D/Projet"); phone.folder("/storage/1A2B-3C4D/Projet/src")
        phone.file("/storage/1A2B-3C4D/Projet/src/main.kt", "fun main()"); phone.file("/storage/1A2B-3C4D/Projet/lisez-moi", "x")
        val result = upload("/storage/1A2B-3C4D/Projet", "Sauvegardes")
        assertEquals("Sauvegardes\\Projet", result.target)
        assertEquals("fun main()", disk.text("Sauvegardes\\Projet\\src\\main.kt"))
        assertEquals("x", disk.text("Sauvegardes\\Projet\\lisez-moi"))
        assertEquals(2, result.hashes.size)
        assertTrue(parts().isEmpty()); assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `nom refuse par Windows refuse avant d'ecrire`() {
        phone.folder("/sdcard"); phone.file("/sdcard/a:b.txt", "x")
        expect<IllegalArgumentException> { upload("/sdcard/a:b.txt", "") }
        assertTrue(disk.names().isEmpty())
    }

    // ─────────────────────────────── Conflits à l'envoi ───────────────────────────────

    @Test
    fun `conflit sans choix refuse avec CONFLICT`() {
        disk.file("rapport.pdf", "serveur")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "téléphone")
        val failure = expect<TransferFailure> { upload("/sdcard/rapport.pdf", "") }
        assertEquals(FailureKind.CONFLICT, failure.kind)
        assertEquals("serveur", disk.text("rapport.pdf"))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `conflit detecte sans tenir compte de la casse`() {
        disk.file("RAPPORT.PDF", "serveur")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "téléphone")
        expect<TransferFailure> { upload("/sdcard/rapport.pdf", "") }
    }

    @Test
    fun `ignorer laisse le serveur intact`() {
        disk.file("rapport.pdf", "serveur")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "téléphone")
        expect<TransferSkipped> { upload("/sdcard/rapport.pdf", "", ConflictAction.SKIP) }
        assertEquals("serveur", disk.text("rapport.pdf"))
    }

    @Test
    fun `renommer donne nom (1)`() {
        disk.file("rapport.pdf", "serveur")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "téléphone")
        assertEquals("rapport (1).pdf", upload("/sdcard/rapport.pdf", "", ConflictAction.RENAME).target)
        assertEquals("serveur", disk.text("rapport.pdf"))
        assertEquals("téléphone", disk.text("rapport (1).pdf"))
    }

    @Test
    fun `remplacer passe par un partiel verifie`() {
        disk.file("rapport.pdf", "ancienne version")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "nouvelle")
        upload("/sdcard/rapport.pdf", "", ConflictAction.REPLACE)
        assertEquals("nouvelle", disk.text("rapport.pdf"))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `remplacer interrompu garde l'ancienne version`() {
        disk.file("rapport.pdf", "ancienne version")
        phone.folder("/sdcard"); phone.file("/sdcard/rapport.pdf", "nouvelle version plus longue")
        disk.failWriteAfter = 4
        expect<SmbTransfers.Failed> { upload("/sdcard/rapport.pdf", "", ConflictAction.REPLACE) }
        assertEquals("ancienne version", disk.text("rapport.pdf"))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `fusionner ajoute les manquants ignore les identiques renomme les differents`() {
        disk.folder("Album"); disk.file("Album\\a.jpg", "A"); disk.file("Album\\b.jpg", "ancien B")
        phone.folder("/sdcard/Album"); phone.file("/sdcard/Album/a.jpg", "A")
        phone.file("/sdcard/Album/b.jpg", "nouveau B"); phone.file("/sdcard/Album/c.jpg", "C")
        val result = upload("/sdcard/Album", "", ConflictAction.MERGE, hash = true)
        assertEquals("Album", result.target)
        assertEquals("A", disk.text("Album\\a.jpg"))
        assertEquals("ancien B", disk.text("Album\\b.jpg"))
        assertEquals("nouveau B", disk.text("Album\\b (1).jpg"))
        assertEquals("C", disk.text("Album\\c.jpg"))
        assertEquals(3, result.hashes.size)
    }

    @Test
    fun `remplacer sur un dossier fusionne et remplace les fichiers differents`() {
        disk.folder("Album"); disk.file("Album\\b.jpg", "ancien B")
        phone.folder("/sdcard/Album"); phone.file("/sdcard/Album/b.jpg", "nouveau B")
        upload("/sdcard/Album", "", ConflictAction.REPLACE)
        assertEquals("nouveau B", disk.text("Album\\b.jpg"))
        assertNull(disk.text("Album\\b (1).jpg"))
    }

    @Test
    fun `fichier contre dossier refuse`() {
        disk.folder("Album")
        phone.folder("/sdcard"); phone.file("/sdcard/Album", "je suis un fichier")
        val failure = expect<TransferFailure> { upload("/sdcard/Album", "", ConflictAction.REPLACE) }
        assertEquals(FailureKind.CONFLICT, failure.kind)
    }

    @Test
    fun `fichier identique ignore`() {
        disk.file("a.txt", "même")
        phone.folder("/sdcard"); phone.file("/sdcard/a.txt", "même")
        expect<TransferSkipped> { upload("/sdcard/a.txt", "", ConflictAction.MERGE, hash = true) }
    }

    @Test
    fun `detection des conflits avant l'envoi insensible a la casse`() {
        fun item(name: String, dir: Boolean = false) = FileItem(path = "/x/$name", name = name, size = 1,
            lastModified = java.util.Date(0), isDirectory = dir, mimeType = null)
        val (conflicts, existing) = conflictsWith(listOf(item("Photo.JPG"), item("neuf.txt")), listOf(item("photo.jpg"), item("autre")))
        assertEquals(listOf("Photo.JPG"), conflicts.map { it.name })
        assertEquals("photo.jpg", existing["photo.jpg"]?.name)
    }

    // ─────────────────────────────── Nettoyage d'un envoi annulé ou interrompu ───────────────

    @Test
    fun `envoi annule partiel et dossier crees supprimes`() {
        disk.folder("Cible")
        phone.folder("/sdcard/Film"); phone.file("/sdcard/Film/film.mkv", "v".repeat(5000))
        disk.failWriteAfter = 300
        disk.writeFailure = { CancellationException("Annulé") }
        expect<CancellationException> { upload("/sdcard/Film", "Cible") }
        assertTrue("partiel supprimé : ${disk.names()}", parts().isEmpty())
        assertFalse("dossier créé et resté vide supprimé", disk.exists("Cible\\Film"))
        assertTrue(receiptFiles().isEmpty())
    }

    @Test
    fun `envoi interrompu les fichiers deja verifies sont conserves`() {
        phone.folder("/sdcard/Docs"); phone.file("/sdcard/Docs/a.txt", "petit"); phone.file("/sdcard/Docs/b.bin", "b".repeat(4000))
        disk.failWriteAfter = 1000
        expect<SmbTransfers.Failed> { upload("/sdcard/Docs", "") }
        assertEquals("petit", disk.text("Docs\\a.txt"))
        assertNull(disk.text("Docs\\b.bin"))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `serveur perdu pendant l'envoi partiel retire a la connexion suivante`() {
        phone.folder("/sdcard"); phone.file("/sdcard/iso.img", "i".repeat(3000))
        disk.failWriteAfter = 700; disk.unreachableAfterWriteFailure = true
        val failure = expect<SmbTransfers.Failed> { upload("/sdcard/iso.img", "") }
        assertTrue(failure.leftover)
        disk.unreachable = false
        assertEquals(1, parts().size)
        transfers.cleanDeferred(volume, disk)
        assertTrue(parts().isEmpty())
    }

    // ─────────────────────────────── Déplacer : source vérifiée avant suppression ─────────────

    @Test
    fun `source inchangee acceptee`() = runBlocking {
        phone.folder("/sdcard/D"); phone.file("/sdcard/D/a", "1"); phone.file("/sdcard/D/b", "2")
        val result = upload("/sdcard/D", "", moving = true)
        transfers.verifyUnchanged(phone, "/sdcard/D", result.hashes)
    }

    @Test
    fun `source modifiee pendant l'envoi refusee`() = runBlocking {
        phone.folder("/sdcard/D"); phone.file("/sdcard/D/a", "1")
        val result = upload("/sdcard/D", "", moving = true)
        phone.file("/sdcard/D/a", "modifié")
        expect<IllegalStateException> { runBlocking { transfers.verifyUnchanged(phone, "/sdcard/D", result.hashes) } }
        Unit
    }

    @Test
    fun `fichier ajoute pendant l'envoi refuse`() = runBlocking {
        phone.folder("/sdcard/D"); phone.file("/sdcard/D/a", "1")
        val result = upload("/sdcard/D", "", moving = true)
        phone.file("/sdcard/D/nouveau", "2")
        expect<IllegalStateException> { runBlocking { transfers.verifyUnchanged(phone, "/sdcard/D", result.hashes) } }
        Unit
    }

    // ─────────────────────────────── Nouveau dossier, renommer ───────────────────────────────

    @Test
    fun `creer un dossier`() {
        disk.folder("Photos")
        assertEquals("Photos\\2025", transfers.createFolder(disk, "Photos", "2025"))
        assertNotNull(disk.stat("Photos\\2025"))
    }

    @Test
    fun `creer un dossier deja existant refuse`() {
        disk.folder("Photos"); disk.folder("Photos\\Vacances")
        val e = expect<IllegalStateException> { transfers.createFolder(disk, "Photos", "vacances") }
        assertTrue(e.message!!.contains("existe déjà"))
    }

    @Test
    fun `creer un dossier au nom invalide refuse`() {
        expect<IllegalArgumentException> { transfers.createFolder(disk, "", "a/b") }
        expect<IllegalArgumentException> { transfers.createFolder(disk, "", "CON") }
        expect<IllegalArgumentException> { transfers.createFolder(disk, "", "fin.") }
        assertTrue(disk.names().isEmpty())
    }

    @Test
    fun `renommer vers un nom existant refuse`() {
        disk.file("a.txt", "A"); disk.file("b.txt", "B")
        val e = expect<IllegalStateException> { transfers.renameEntry(disk, "a.txt", "B.TXT") }
        assertTrue(e.message!!.contains("existe déjà"))
        assertEquals("A", disk.text("a.txt")); assertEquals("B", disk.text("b.txt"))
    }

    @Test
    fun `renommer un fichier`() {
        disk.folder("Docs"); disk.file("Docs\\brouillon.txt", "x")
        assertEquals("Docs\\final.txt", transfers.renameEntry(disk, "Docs\\brouillon.txt", "final.txt"))
        assertEquals("x", disk.text("Docs\\final.txt")); assertFalse(disk.exists("Docs\\brouillon.txt"))
    }

    @Test
    fun `renommer un dossier avec son contenu`() {
        disk.folder("Ancien"); disk.file("Ancien\\f.txt", "contenu")
        transfers.renameEntry(disk, "Ancien", "Nouveau")
        assertEquals("contenu", disk.text("Nouveau\\f.txt"))
        assertFalse(disk.exists("Ancien"))
    }

    @Test
    fun `changer seulement la casse est accepte`() {
        disk.file("photo.jpg", "x")
        assertEquals("Photo.JPG", transfers.renameEntry(disk, "photo.jpg", "Photo.JPG"))
        assertEquals("Photo.JPG", disk.stat("photo.jpg")?.name)
    }

    @Test
    fun `renommer en bonobo-part refuse`() {
        disk.file("a.txt", "x")
        expect<IllegalArgumentException> { transfers.renameEntry(disk, "a.txt", "a.txt.bonobo-part") }
    }

    @Test
    fun `regles de nom Windows`() {
        assertNull(SmbPaths.nameError("Vacances d'été 2025"))
        assertNull(SmbPaths.nameError("CONTRAT.pdf"))
        assertNotNull(SmbPaths.nameError(""))
        assertNotNull(SmbPaths.nameError(".."))
        assertNotNull(SmbPaths.nameError("a?b"))
        assertNotNull(SmbPaths.nameError("nul.txt"))
        assertNotNull(SmbPaths.nameError("COM1"))
        assertNotNull(SmbPaths.nameError("fin "))
        assertNotNull(SmbPaths.nameError(" début"))
        assertNotNull(SmbPaths.nameError("x".repeat(256)))
    }

    // ─────────────────────────────── Instantané serveur → téléphone ───────────────────────────

    @Test
    fun `instantane detecte un changement de la source serveur`() {
        disk.folder("D"); disk.file("D\\a", "1"); disk.folder("D\\s"); disk.file("D\\s\\b", "22")
        val before = transfers.snapshot(disk, "D")
        assertEquals(setOf("", "a", "s", "s\\b"), before.keys)
        assertEquals(before, transfers.snapshot(disk, "D"))
        disk.file("D\\s\\b", "modifié")
        assertFalse(before == transfers.snapshot(disk, "D"))
    }
}
