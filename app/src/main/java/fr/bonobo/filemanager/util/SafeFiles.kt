package fr.bonobo.filemanager.util

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Shared checks for user supplied names, archive paths and non-destructive writes. */
object SafeFiles {
    fun child(parent: File, name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Nom de fichier invalide" }
        return File(parent, name)
    }

    fun archiveChild(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
            normalized.split('/').none { it == ".." } && '\u0000' !in normalized) {
            "Chemin dangereux dans l'archive"
        }
        val base = root.canonicalFile.toPath()
        val destination = File(root, normalized)
        require(destination.canonicalFile.toPath().startsWith(base) &&
            destination.canonicalFile.toPath() != base) { "Entrée hors du dossier d'extraction" }
        var part: File? = destination.absoluteFile
        while (part != null && part.toPath() != root.absoluteFile.toPath()) {
            require(!Files.isSymbolicLink(part.toPath())) { "Lien symbolique interdit" }
            part = part.parentFile
        }
        return destination
    }

    fun writeNew(destination: File, write: (OutputStream) -> Unit) {
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
            "Dossier de destination inaccessible"
        }
        // CREATE_NEW is an atomic reservation, including against existing symlinks.
        val output = Files.newOutputStream(destination.toPath(),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            output.use(write)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun publish(temporary: File, destination: File) {
        writeNew(destination) { output -> temporary.inputStream().use { it.copyTo(output) } }
        temporary.delete()
    }

    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
