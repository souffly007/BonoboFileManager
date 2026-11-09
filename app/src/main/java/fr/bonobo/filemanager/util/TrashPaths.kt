package fr.bonobo.filemanager.util

import java.io.File
import java.util.Base64

/** Decode only unambiguous legacy records; never guess a truncated origin. */
object TrashPaths {
    fun origin(name: String): String {
        require(name.startsWith("trash_") && name.contains("_#_")) { "Ancienne entrée ambiguë : choisissez un dossier de restauration" }
        val encoded = name.removePrefix("trash_").substringBefore("_#_")
        val path = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        require(path.startsWith("/") && path.none { it.isISOControl() }) { "Chemin de restauration invalide : choisissez un dossier" }
        return path
    }
    fun destination(path: String, trash: File): File {
        require(File(path).isAbsolute && path.none { it.isISOControl() }) { "Chemin de restauration invalide" }
        val target = File(path).canonicalFile
        require(target.parentFile != null && !target.toPath().startsWith(trash.canonicalFile.toPath())) { "Chemin de restauration invalide" }
        require(!SafeFiles.exists(target)) { "La destination existe déjà : choisissez un autre dossier" }
        return target
    }
    fun displayName(name: String): String = if (name.startsWith("trash_") && name.contains("_#_"))
        name.substringAfter("_#_") else name
}
