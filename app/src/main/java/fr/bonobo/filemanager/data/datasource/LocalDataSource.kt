package fr.bonobo.filemanager.data.datasource

import fr.bonobo.filemanager.data.local.dao.FavoriteDao
import fr.bonobo.filemanager.data.local.dao.HistoryDao
import javax.inject.Inject

class LocalDataSource @Inject constructor(
    val favoriteDao: FavoriteDao,
    val historyDao: HistoryDao
)
