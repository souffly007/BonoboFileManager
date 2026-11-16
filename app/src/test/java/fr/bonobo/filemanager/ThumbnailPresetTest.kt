package fr.bonobo.filemanager

import fr.bonobo.filemanager.presentation.components.ThumbnailPreset
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPresetTest {
    @Test fun oldSettingsRemainUsableAndSizesIncrease() {
        val keys = listOf("SMALL", "MEDIUM", "LARGE", "XLARGE", "XXLARGE", "HUGE")
        val values = keys.map { ThumbnailPreset.fromKey(it) }
        assertEquals(keys, values.map { it.name })
        assertTrue(values.zipWithNext().all { (a,b) -> a.listSize < b.listSize && a.gridWidth < b.gridWidth })
        assertEquals(ThumbnailPreset.MEDIUM, ThumbnailPreset.fromKey("unknown"))
    }
}
