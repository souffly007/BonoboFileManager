package fr.bonobo.filemanager.util

import java.io.File
import java.nio.file.Files

object LocalIntegrityAccess : IntegrityVerifier.Access {
    override fun identity(path: String) = File(path).canonicalPath
    override fun children(path: String): List<IntegrityVerifier.Child>? {
        val file = File(path)
        require(!Files.isSymbolicLink(file.toPath())) { "Lien symbolique refusé" }
        check(file.exists()) { "Fichier inaccessible pendant la vérification" }
        return if (file.isDirectory) (file.listFiles() ?: error("Dossier illisible")).map { IntegrityVerifier.Child(it.name, it.path) }
        else { check(file.isFile) { "Type de source non pris en charge" }; null }
    }
    override fun open(path: String) = File(path).also {
        require(!Files.isSymbolicLink(it.toPath())) { "Lien symbolique refusé" }
    }.inputStream()
}
