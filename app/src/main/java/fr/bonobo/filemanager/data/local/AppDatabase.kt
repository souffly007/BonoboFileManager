package fr.bonobo.filemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fr.bonobo.filemanager.data.local.dao.*
import fr.bonobo.filemanager.data.local.entity.*

@Database(
    entities = [
        FileEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        RemoteConnectionEntity::class,
        BookmarkEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileDao(): FileDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun historyDao(): HistoryDao

    abstract fun remoteConnectionDao(): RemoteConnectionDao
    
    abstract fun bookmarkDao(): BookmarkDao
}
