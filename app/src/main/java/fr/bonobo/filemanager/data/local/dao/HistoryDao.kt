package fr.bonobo.filemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.bonobo.filemanager.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("""
        SELECT * FROM history
        ORDER BY accessedAt DESC
        LIMIT 50
    """)
    fun getHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(
        item: HistoryEntity
    )

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}
