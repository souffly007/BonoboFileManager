package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Identity of a file that survives a reboot and a USB remount. Shared by [ArtifactCleanup]
 * (partial files) and [TrashRestorer] (restore receipts).
 *
 *  - SAF: the document id / URI returned by the provider ([document]);
 *  - otherwise: volume UUID + path relative to the volume root + size + modification date.
 *
 * fileKey (device + inode) and creationTime are never used: on Android, FUSE assigns the device
 * number at mount time, vfat/exFAT inodes are renumbered on remount, and creationTime() is in
 * fact the modification time (FAT/exFAT have no reliable birth time).
 */
data class FileIdentity(
    val document: String? = null,
    val volume: String? = null,
    val relativePath: String? = null,
    val directory: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
) {
    enum class Field { DOCUMENT, VOLUME, PATH, TYPE, SIZE, MODIFIED, CONTENT, FORMAT }

    /** Expected (recorded) vs found (current) value; numbers only, so they can enter the journal. */
    data class Mismatch(val field: Field, val expected: Long? = null, val found: Long? = null) {
        /** Precise journal code (closed vocabulary). */
        fun result(partial: Boolean = false): Result = when (field) {
            Field.DOCUMENT -> Result.DOCUMENT_MISMATCH
            Field.VOLUME -> Result.VOLUME_MISMATCH
            Field.PATH -> Result.PATH_MISMATCH
            Field.TYPE -> Result.TYPE_MISMATCH
            Field.SIZE -> Result.SIZE_MISMATCH
            Field.MODIFIED -> if (partial) Result.MODIFIED_AFTER_INTERRUPTION else Result.MODIFIED_MISMATCH
            Field.CONTENT -> Result.MISMATCH
            Field.FORMAT -> Result.IDENTITY_MISMATCH
        }
        /** Short French explanation for the user. */
        fun describe(): String = when (field) {
            Field.DOCUMENT -> "le document n’est plus le même"
            Field.VOLUME -> "il ne se trouve plus sur le même support"
            Field.PATH -> "son emplacement a changé"
            Field.TYPE -> "un dossier a remplacé un fichier (ou l’inverse)"
            Field.SIZE -> "la taille a changé (${size(expected)} attendus, ${size(found)} trouvés)"
            Field.MODIFIED -> "la date de modification a changé (${date(expected)} attendu, ${date(found)} trouvé)"
            Field.CONTENT -> "son contenu a changé depuis la restauration précédente"
            Field.FORMAT -> "le reçu précédent a été écrit par une ancienne version et ne peut pas être vérifié"
        }
        private fun size(value: Long?) = value?.let { fr.bonobo.filemanager.domain.transfer.TransferText.size(it) } ?: "?"
        private fun date(value: Long?) = value?.let {
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date(it))
        } ?: "?"
    }

    fun encode(): String = listOf("v3", "doc" to document, "vol" to volume, "rel" to relativePath,
        "type" to if (directory) "d" else "f", "size" to size?.toString(), "mtime" to modified?.toString())
        .joinToString(";") { part ->
            if (part is Pair<*, *>) "${part.first}=${(part.second as String?)?.let { URLEncoder.encode(it, "UTF-8") } ?: ""}"
            else part.toString()
        }

    companion object {
        /** FAT stores modification times with a 2 s granularity (exFAT: 10 ms). */
        const val MTIME_TOLERANCE_MS = 2_000L

        fun decode(value: String): FileIdentity? {
            val parts = value.split(';')
            if (parts.firstOrNull() != "v3") return null
            val fields = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun text(key: String) = fields[key]?.takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
            return FileIdentity(text("doc"), text("vol"), text("rel"), fields["type"] == "d",
                text("size")?.toLongOrNull(), text("mtime")?.toLongOrNull())
        }

        /**
         * Local file. [volumeOf] gives the UUID and root of the volume holding a path (null if unknown:
         * the absolute canonical path is then used as relative path, volume null).
         */
        fun local(file: File, volumeOf: (String) -> Pair<String?, File?>?): FileIdentity {
            val canonical = file.canonicalFile
            val (uuid, root) = volumeOf(canonical.path) ?: (null to null)
            val relative = root?.canonicalFile?.let { base ->
                canonical.path.removePrefix(base.path).trimStart('/').takeIf { canonical.path.startsWith(base.path) }
            } ?: canonical.path
            val attributes = java.nio.file.Files.readAttributes(canonical.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            return FileIdentity(null, uuid ?: if (root != null) "primary" else null, relative, attributes.isDirectory,
                if (attributes.isDirectory) null else attributes.size(), attributes.lastModifiedTime().toMillis())
        }

        /**
         * Compare a recorded identity with the current one.
         * @param partial a partial file (.bonobo-part): any size ≤ the recorded one (bytes sent) is accepted
         *   — a key pulled out before the cache was flushed leaves a smaller file — and only a modification
         *   AFTER the recorded date is refused. Otherwise size must be equal and the date equal within
         *   [MTIME_TOLERANCE_MS].
         * @return null when it is the same file.
         */
        fun compare(expected: FileIdentity, found: FileIdentity, partial: Boolean = false): Mismatch? {
            if (expected.document != null && found.document != null) {
                if (expected.document != found.document) return Mismatch(Field.DOCUMENT)
            } else {
                if (expected.volume != null && found.volume != null && !expected.volume.equals(found.volume, ignoreCase = true))
                    return Mismatch(Field.VOLUME)
                if (expected.relativePath != null && found.relativePath != null && expected.relativePath != found.relativePath)
                    return Mismatch(Field.PATH, expected.relativePath.length.toLong(), found.relativePath.length.toLong())
            }
            if (expected.directory != found.directory)
                return Mismatch(Field.TYPE, if (expected.directory) 1 else 0, if (found.directory) 1 else 0)
            if (!expected.directory && expected.size != null && found.size != null &&
                (if (partial) found.size > expected.size else found.size != expected.size))
                return Mismatch(Field.SIZE, expected.size, found.size)
            if (expected.modified != null && found.modified != null) {
                val later = found.modified > expected.modified + MTIME_TOLERANCE_MS
                val different = kotlin.math.abs(found.modified - expected.modified) > MTIME_TOLERANCE_MS
                if (if (partial) later else different) return Mismatch(Field.MODIFIED, expected.modified, found.modified)
            }
            return null
        }
    }
}
