package fr.bonobo.filemanager.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.bonobo.filemanager.data.repository.FileRepositoryImpl
import fr.bonobo.filemanager.data.repository.StorageRepositoryImpl
import fr.bonobo.filemanager.domain.repository.IFileRepository
import fr.bonobo.filemanager.domain.repository.IStorageRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFileRepository(
        implementation: FileRepositoryImpl
    ): IFileRepository

    @Binds
    @Singleton
    abstract fun bindStorageRepository(
        implementation: StorageRepositoryImpl
    ): IStorageRepository

    @Binds
    @Singleton
    abstract fun bindAppRepository(
        implementation: fr.bonobo.filemanager.data.repository.AppRepositoryImpl
    ): fr.bonobo.filemanager.domain.repository.IAppRepository

    @Binds
    @Singleton
    abstract fun bindRemoteRepository(
        implementation: fr.bonobo.filemanager.data.repository.RemoteRepositoryImpl
    ): fr.bonobo.filemanager.domain.repository.IRemoteRepository
}
