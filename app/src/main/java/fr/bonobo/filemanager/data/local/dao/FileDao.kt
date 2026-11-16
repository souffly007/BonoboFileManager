package fr.bonobo.filemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.bonobo.filemanager.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<FileEntity>)

    @Query("SELECT * FROM files WHERE path LIKE :query ORDER BY isDirectory DESC, name ASC")
    suspend fun search(query: String): List<FileEntity>

    @Query("DELETE FROM files")
    suspend fun clear()
}
