package fr.bonobo.filemanager.domain.repository

interface ICloudRepository {

    suspend fun upload(
        localPath: String,
        remotePath: String
    ): Result<Unit>

    suspend fun download(
        remotePath: String,
        localPath: String
    ): Result<Unit>

    suspend fun sync(): Result<Unit>
}
