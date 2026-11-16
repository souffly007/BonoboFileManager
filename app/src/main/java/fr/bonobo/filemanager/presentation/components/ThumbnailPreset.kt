package fr.bonobo.filemanager.presentation.components

/** Stored keys from older versions remain valid. Sizes are logical dp. */
enum class ThumbnailPreset(val label: String, val listSize: Int, val gridWidth: Int) {
    SMALL("Compact", 48, 112),
    MEDIUM("Confort", 64, 136),
    LARGE("Grand", 88, 160),
    XLARGE("Très grand", 112, 184),
    XXLARGE("XL", 136, 216),
    HUGE("XXL", 160, 248);
    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.name == key } ?: MEDIUM
    }
}
