package fr.bonobo.filemanager.domain.model

sealed interface FileOperation {

    data class Copy(
        val source: String,
        val destination: String
    ) : FileOperation

    data class Move(
        val source: String,
        val destination: String
    ) : FileOperation

    data class Delete(
        val path: String
    ) : FileOperation

    data class Rename(
        val path: String,
        val newName: String
    ) : FileOperation

    data class Compress(
        val paths: List<String>,
        val destination: String
    ) : FileOperation

    data class Decompress(
        val archive: String,
        val destination: String
    ) : FileOperation
}