package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.util.IntegrityVerifier
import java.io.InputStream
import java.io.OutputStream

/**
 * What the SMB logic needs from one share. Paths are relative to the share, « \ » separated,
 * "" being the share root. Implemented over smbj (SmbClientRepository) and in memory for tests.
 */
interface SmbDisk {
    /** null when the element does not exist. */
    fun stat(path: String): SmbNode?
    /** Children of a folder, without « . » and « .. ». */
    fun list(path: String): List<SmbNode>
    fun openRead(path: String): InputStream
    /** Creates a NEW file (fails if anything exists under this name). */
    fun createNew(path: String): OutputStream
    /** Creates a NEW folder (fails if anything exists under this name). */
    fun mkdir(path: String)
    /** Server-side rename; [replace] = atomically replace an existing file. */
    fun rename(from: String, to: String, replace: Boolean)
    fun deleteFile(path: String)
    /** Deletes a folder only if it is empty. */
    fun deleteEmptyDirectory(path: String)
    fun deleteTree(path: String)
    /** Free bytes for this user on the share, null when the server does not say. */
    fun freeSpace(): Long? = null
}

/** [modified]: last write time (ms), [changed]: change time (ms, also moves on metadata changes). */
data class SmbNode(val name: String, val directory: Boolean, val size: Long, val modified: Long,
    val changed: Long = modified, val reparse: Boolean = false)

/** SHA-256 verification over an [SmbDisk], with the reparse-point refusal. */
fun SmbDisk.integrityAccess(): IntegrityVerifier.Access = object : IntegrityVerifier.Access {
    override fun identity(path: String) = SmbPaths.identity(path)
    override fun children(path: String): List<IntegrityVerifier.Child>? {
        val node = stat(path) ?: error("Document SMB inaccessible")
        require(!node.reparse) { "Point de réanalyse SMB refusé" }
        if (!node.directory) return null
        return list(path).map {
            require(!it.reparse) { "Point de réanalyse SMB refusé" }
            IntegrityVerifier.Child(it.name, SmbPaths.join(path, it.name))
        }
    }
    override fun open(path: String): InputStream = openRead(path)
}
