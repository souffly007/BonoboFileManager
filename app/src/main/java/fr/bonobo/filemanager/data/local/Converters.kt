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
        // Les anciennes connexions FTP sont relues en FTPS afin qu'aucun
        // enregistrement existant ne puisse réactiver une connexion en clair.
        return if (value.equals("FTP", ignoreCase = true)) {
            ConnectionType.FTPS
        } else {
            ConnectionType.valueOf(value)
        }
    }
}
