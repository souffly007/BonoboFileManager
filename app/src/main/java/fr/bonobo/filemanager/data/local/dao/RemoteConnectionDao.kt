package fr.bonobo.filemanager.data.local.dao

import androidx.room.*
import fr.bonobo.filemanager.data.local.entity.RemoteConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteConnectionDao {
    @Query("SELECT * FROM remote_connections")
    fun getAllConnections(): Flow<List<RemoteConnectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnection(connection: RemoteConnectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(connections: List<RemoteConnectionEntity>)

    @Query("UPDATE remote_connections SET pass = :encrypted, credentialsEncrypted = 1 WHERE id = :id AND credentialsEncrypted = 0 AND pass = :original")
    suspend fun migratePassword(id: Long, original: String, encrypted: String)

    @Query("DELETE FROM remote_connections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun deleteConnection(connection: RemoteConnectionEntity)
}
