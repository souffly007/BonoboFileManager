package fr.bonobo.filemanager.util

/** A tree grant describes only the selected tree, never every document on its volume. */
object UsbAccessScope {
    enum class Scope { VOLUME_ROOT, FOLDER, UNKNOWN }

    fun volumeId(treeId: String): String = treeId.substringBefore(':')

    fun classify(treeId: String, volumeUuid: String?): Scope {
        if (volumeUuid.isNullOrBlank() || ':' !in treeId || !volumeId(treeId).equals(volumeUuid, ignoreCase = true)) return Scope.UNKNOWN
        return if (treeId.substringAfter(':', missingDelimiterValue = "").isEmpty()) Scope.VOLUME_ROOT else Scope.FOLDER
    }

    fun label(scope: Scope): String = when (scope) {
        Scope.VOLUME_ROOT -> "Clé USB : racine autorisée"
        Scope.FOLDER -> "Dossier autorisé sur la clé USB"
        Scope.UNKNOWN -> "Emplacement autorisé"
    }

    const val ROOT_SELECTION_REJECTED = "Android n'a pas accordé la racine de la clé. Si elle est désactivée dans le sélecteur, choisissez un dossier de la clé avec « Sélectionner un emplacement »."
}
