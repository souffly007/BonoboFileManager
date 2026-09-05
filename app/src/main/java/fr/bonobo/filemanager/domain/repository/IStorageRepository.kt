package fr.bonobo.filemanager.domain.repository

import fr.bonobo.filemanager.domain.model.StorageInfo

interface IStorageRepository {

    fun getInternalStorageInfo(): StorageInfo
}
