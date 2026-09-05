package fr.bonobo.filemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.bonobo.filemanager.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("""
        SELECT * FROM favorites
        ORDER BY addedAt DESC
    """)
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(
        item: FavoriteEntity
    )

    @Query("""
        DELETE FROM favorites
        WHERE path = :path
    """)
    suspend fun deleteByPath(
        path: String
    )

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM favorites
            WHERE path = :path
        )
    """)
    suspend fun isFavorite(
        path: String
    ): Boolean
}