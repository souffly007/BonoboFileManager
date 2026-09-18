package fr.bonobo.filemanager.domain.model

data class RemoteConnection(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val pass: String = "",
    val type: ConnectionType = ConnectionType.FTPS,
    val share: String? = null
)

enum class ConnectionType {
    /** Legacy value kept only so old saved configurations can be migrated safely. */
    FTP, FTPS, SMB, SFTP, GOOGLE_DRIVE
}
