package fr.bonobo.filemanager.util

import java.io.File

/** Mapping restricted to Android's external-storage provider; other IDs stay opaque. */
object StorageEquivalence {
    fun local(authority: String?, documentId: String, volumes: Map<String, File>): File? {
        if (authority != "com.android.externalstorage.documents") return null
        val separator = documentId.indexOf(':')
        require(separator >= 1) { "Identifiant de stockage invalide" }
        val volume = documentId.substring(0, separator)
        val root = volumes.entries.singleOrNull { it.key.equals(volume, ignoreCase = true) }?.value ?: return null
        val relative = documentId.substring(separator + 1)
        require(!relative.startsWith('/') && relative.split('/').none { it == "." || it == ".." } && relative.none { it == '\\' || it.isISOControl() }) { "Identifiant de stockage dangereux" }
        val resolved = if (relative.isEmpty()) root.canonicalFile else File(root, relative).canonicalFile
        require(resolved.toPath().startsWith(root.canonicalFile.toPath())) { "Alias sortant du volume" }
        return resolved
    }
    fun requireOutsideTrash(parent: File, trash: File) {
        require(!parent.canonicalFile.toPath().startsWith(trash.canonicalFile.toPath())) { "Restauration dans la corbeille ou sur elle-même interdite" }
    }
}
