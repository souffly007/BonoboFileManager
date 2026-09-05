package fr.bonobo.filemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.bonobo.filemanager.domain.model.ConnectionType

@Entity(tableName = "remote_connections")
data class RemoteConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val type: ConnectionType,
    val share: String? = null
)
