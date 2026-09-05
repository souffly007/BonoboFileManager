package fr.bonobo.filemanager.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.bonobo.filemanager.data.local.AppDatabase
import fr.bonobo.filemanager.data.local.dao.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bonobo_database"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideFileDao(
        database: AppDatabase
    ): FileDao = database.fileDao()

    @Provides
    fun provideFavoriteDao(
        database: AppDatabase
    ): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideHistoryDao(
        database: AppDatabase
    ): HistoryDao = database.historyDao()

    @Provides
    fun provideRemoteConnectionDao(
        database: AppDatabase
    ): RemoteConnectionDao = database.remoteConnectionDao()

    @Provides
    fun provideBookmarkDao(
        database: AppDatabase
    ): BookmarkDao = database.bookmarkDao()
}
