package fr.bonobo.filemanager.data.local

import androidx.room.TypeConverter
import fr.bonobo.filemanager.domain.model.ConnectionType

class Converters {
    @TypeConverter
    fun fromConnectionType(value: ConnectionType): String {
        return value.name
    }

    @TypeConverter
    fun toConnectionType(value: String): ConnectionType {
        return ConnectionType.valueOf(value)
    }
}
