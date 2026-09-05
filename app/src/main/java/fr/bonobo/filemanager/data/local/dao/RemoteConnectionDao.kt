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

    @Delete
    suspend fun deleteConnection(connection: RemoteConnectionEntity)
}
