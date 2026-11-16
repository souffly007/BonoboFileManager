package fr.bonobo.filemanager.util

import java.io.File
import java.util.Base64

/**
 * Trash entry names: « trash_<origine base64url>~<id>_#_<nom> ».
 *
 * [id] is unique per entry (random, hex). Restore receipts are associated with it — never with the
 * name or the path, which are identical when the same file is put in the trash twice. Entries written
 * by older versions (« trash_<origine>_#_<nom> ») have no id and are still understood.
 *
 * Decode only unambiguous legacy records; never guess a truncated origin.
 */
object TrashPaths {
    private const val ID_SEPARATOR = '~' // not part of the base64url alphabet

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    fun entryName(origin: String, name: String, id: String): String {
        require(id.isNotEmpty() && id.all { it in '0'..'9' || it in 'a'..'f' }) { "Identifiant de corbeille invalide" }
        val encoded = Base64.getUrlEncoder().encodeToString(origin.toByteArray(Charsets.UTF_8))
        return "trash_$encoded$ID_SEPARATOR${id}_#_$name"
    }

    /** Unique id of the entry, null for an entry created by an older version. */
    fun entryId(name: String): String? {
        if (!name.startsWith("trash_") || !name.contains("_#_")) return null
        return name.removePrefix("trash_").substringBefore("_#_").substringAfter(ID_SEPARATOR, "")
            .takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    fun origin(name: String): String {
        require(name.startsWith("trash_") && name.contains("_#_")) { "Ancienne entrée ambiguë : choisissez un dossier de restauration" }
        val encoded = name.removePrefix("trash_").substringBefore("_#_").substringBefore(ID_SEPARATOR)
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
