package fr.bonobo.filemanager.data.datasource

import javax.inject.Inject

class RemoteDataSource @Inject constructor() {

    suspend fun upload(
        localPath: String,
        remotePath: String
    ): Result<Unit> {
        return Result.failure(
            NotImplementedError("Aucun fournisseur cloud configuré")
        )
    }

    suspend fun download(
        remotePath: String,
        localPath: String
    ): Result<Unit> {
        return Result.failure(
            NotImplementedError("Aucun fournisseur cloud configuré")
        )
    }
}
