package fr.bonobo.filemanager.domain.repository

import fr.bonobo.filemanager.domain.model.AppItem

interface IAppRepository {
    suspend fun getInstalledApps(): List<AppItem>
    suspend fun backupApp(app: AppItem, destinationPath: String? = null): Result<String>
}
