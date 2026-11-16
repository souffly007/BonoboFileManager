package fr.bonobo.filemanager.data.repository

import java.util.Locale

/**
 * Pure SMB path rules (no network, no Android): unit-testable.
 *
 * The UI works with virtual paths ("/", "/Photos", "/Photos/2024"). The « Partage » field of a
 * connection holds either nothing (the first virtual segment is then the share), a share alone
 * ("Documents"), or a share + starting folder ("c/Users/Nico/Desktop"). On the wire, smbj needs the
 * share name and a path relative to it, with « \ » separators ("Users\Nico\Desktop\Photos").
 */
object SmbPaths {

    /** Result of parsing the « Partage » field. */
    sealed interface ShareInput {
        /** [share] null: no share configured. [base] uses « / » separators, without leading/trailing « / ». */
        data class Valid(val share: String?, val base: String, val host: String? = null) : ShareInput {
            /** Stored form of the field: "share" or "share/base", null when no share. */
            val normalized: String? get() = share?.let { if (base.isBlank()) it else "$it/$base" }
        }
        data class Invalid(val message: String) : ShareInput
    }

    /** Share + path relative to the share, ready for smbj. */
    data class Location(val share: String, val relative: String)

    /** Characters Windows refuses in a share name. */
    private val FORBIDDEN_IN_SHARE = "\"/\\[]:|<>+=;,*?".toSet()
    /** Characters Windows refuses in a file or folder name. */
    private val FORBIDDEN_IN_SEGMENT = "\"/\\:|<>*?".toSet()
    private val OTHER_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * Accepts: "", "Documents", "c/Users/Nico", "\c\Users" (one leading separator = no host),
     * "\\hôte\partage\chemin", "//hôte/partage/chemin" and "smb://hôte/partage/chemin" (%20 decoded).
     * Anything else is refused with a short French message.
     */
    fun parseShare(raw: String?): ShareInput {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ShareInput.Valid(null, "")
        val withHost: Boolean
        when {
            text.lowercase(Locale.ROOT).startsWith("smb:") -> {
                text = text.substring(4).replace('\\', '/')
                if (!text.startsWith("//")) return ShareInput.Invalid(
                    "Adresse SMB incomplète. Exemple : smb://192.168.1.10/Documents")
                text = percentDecode(text) ?: return ShareInput.Invalid(
                    "Adresse SMB invalide : caractère encodé (%) incorrect.")
                withHost = true
            }
            OTHER_SCHEME.containsMatchIn(text) -> return ShareInput.Invalid(
                "Seules les adresses smb:// sont acceptées. Exemple : smb://192.168.1.10/Documents")
            else -> withHost = text.startsWith("\\\\") || text.startsWith("//")
        }
        val segments = text.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val host = if (withHost) segments.firstOrNull()
            ?: return ShareInput.Invalid("Adresse SMB sans serveur. Exemple : \\\\192.168.1.10\\Documents")
        else null
        val parts = if (withHost) segments.drop(1) else segments
        if (parts.isEmpty()) return ShareInput.Invalid(
            "Nom du partage manquant après le serveur. Exemple : \\\\${host ?: "serveur"}\\Documents")
        val share = parts.first()
        if (share.any { it in FORBIDDEN_IN_SHARE } || share == "." || share == "..") return ShareInput.Invalid(
            "Nom de partage invalide : « $share ». Indiquez le nom du partage (ex. Documents), pas une lettre de lecteur.")
        for (segment in parts.drop(1)) {
            if (segment == "." || segment == "..") return ShareInput.Invalid(
                "Chemin invalide : « $segment » n’est pas accepté dans le dossier de départ.")
            if (segment.any { it in FORBIDDEN_IN_SEGMENT || it.code < 32 }) return ShareInput.Invalid(
                "Nom de dossier invalide dans le chemin : « $segment ».")
        }
        return ShareInput.Valid(share, parts.drop(1).joinToString("/"), host)
    }

    /** Same as [parseShare] for a stored connection; an invalid value is an explicit error. */
    fun configured(raw: String?): ShareInput.Valid = when (val parsed = parseShare(raw)) {
        is ShareInput.Valid -> parsed
        is ShareInput.Invalid -> throw IllegalArgumentException(parsed.message)
    }

    /** Non-empty segments of a virtual path (« / » or « \ » accepted, doubled and trailing separators ignored). */
    fun segments(virtualPath: String): List<String> =
        virtualPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    /** Share used for [virtualPath]: the configured one, else the first virtual segment. */
    fun shareFor(configured: ShareInput.Valid, virtualPath: String): String? =
        configured.share ?: segments(virtualPath).firstOrNull()

    /** Path relative to the share, « \ » separated; "" is the share root. */
    fun relativePath(configured: ShareInput.Valid, virtualPath: String): String {
        val virtual = segments(virtualPath).let { if (configured.share == null) it.drop(1) else it }
        return (segments(configured.base) + virtual).joinToString("\\")
    }

    /** Share + relative path, or an explicit error when no share can be determined. */
    fun locate(configured: ShareInput.Valid, virtualPath: String): Location {
        val share = shareFor(configured, virtualPath)
            ?: throw IllegalArgumentException("Indiquez un nom de partage (ex. Documents) dans la connexion SMB.")
        return Location(share, relativePath(configured, virtualPath))
    }

    /** Joins two SMB relative paths with « \ ». */
    fun join(parent: String, child: String): String =
        if (parent.isBlank()) child else "${parent.trimEnd('/', '\\')}\\$child"

    /** Virtual path of a listed child: "/" or "" + "a" → "/a", "/x/" + "a" → "/x/a". */
    fun childVirtualPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"

    /** Case-insensitive identity of an SMB relative path (Windows servers ignore case). */
    fun identity(relative: String): String =
        relative.replace('/', '\\').trimEnd('\\').lowercase(Locale.ROOT)

    /** First free « nom (n).ext » next to [requested] (« \ » separated); [requested] itself when free. */
    fun uniqueCandidate(requested: String, exists: (String) -> Boolean): String {
        if (!exists(requested)) return requested
        val normalized = requested.replace('/', '\\')
        val parent = normalized.substringBeforeLast('\\', "")
        val name = normalized.substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidateName = "$stem ($index)$extension"
            val candidate = if (parent.isBlank()) candidateName else "$parent\\$candidateName"
            if (!exists(candidate)) return candidate
            index++
        }
    }

    /**
     * Target of a copy / move / duplicate of [source] into [destinationDirectory] (both relative to the
     * same share). Duplicate: the free name is chosen FIRST, so duplicating in the same folder gives
     * « nom (1).ext » instead of « source et destination identiques ».
     */
    fun planTarget(source: String, destinationDirectory: String, duplicate: Boolean, exists: (String) -> Boolean): String {
        val name = source.replace('/', '\\').trimEnd('\\').substringAfterLast('\\')
        require(name.isNotBlank()) { "Élément source invalide" }
        val requested = join(destinationDirectory, name)
        val target = if (duplicate) uniqueCandidate(requested, exists) else requested
        val sourceId = identity(source)
        val targetId = identity(target)
        require(sourceId != targetId) { "La source et la destination sont identiques" }
        require(!targetId.startsWith("$sourceId\\")) { "Impossible de copier ou déplacer un dossier à l’intérieur de lui-même" }
        check(duplicate || !exists(target)) { "« $name » existe déjà dans le dossier de destination" }
        return target
    }

    /** Decodes %XX (UTF-8) without turning « + » into a space; null on a malformed escape. */
    private fun percentDecode(text: String): String? {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val value = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(value); i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
