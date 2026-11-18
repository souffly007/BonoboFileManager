package fr.bonobo.filemanager

import fr.bonobo.filemanager.data.repository.SmbDisk
import fr.bonobo.filemanager.data.repository.SmbNode
import fr.bonobo.filemanager.data.repository.SmbPaths
import fr.bonobo.filemanager.data.repository.SmbTransfers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SMB share in memory (case-insensitive like Windows), with failure injection for the tests. */
class InMemorySmbDisk : SmbDisk {
    class Item(var path: String, val directory: Boolean, var content: ByteArray = ByteArray(0), var modified: Long = 1_000)

    val items = LinkedHashMap<String, Item>()
    /** A createNew stream throws [writeFailure] once this many bytes were written. */
    var failWriteAfter: Long? = null
    var writeFailure: () -> Throwable = { IOException("Connexion perdue") }
    /** Every operation fails (server unreachable). */
    var unreachable = false
    /** Turned on by the failing write: the server is gone right after the failure. */
    var unreachableAfterWriteFailure = false
    /** Bytes are flipped while written (corruption on the wire → SHA mismatch). */
    var corruptWrites = false
    var opens = 0

    private fun key(path: String) = SmbPaths.identity(path)
    private fun parentKey(path: String) = key(SmbTransfers.parentOf(path))
    private fun reach() { if (unreachable) throw IOException("Serveur injoignable") }

    fun folder(path: String) { items[key(path)] = Item(path, true) }
    fun file(path: String, text: String) { items[key(path)] = Item(path, false, text.toByteArray()) }
    fun text(path: String): String? = items[key(path)]?.takeIf { !it.directory }?.content?.toString(Charsets.UTF_8)
    fun exists(path: String) = key(path) in items
    fun names(): List<String> = items.values.map { it.path }.sorted()

    override fun stat(path: String): SmbNode? {
        reach()
        if (key(path).isEmpty()) return SmbNode("", true, 0, 0)
        val item = items[key(path)] ?: return null
        return SmbNode(SmbTransfers.nameOf(item.path), item.directory, item.content.size.toLong(), item.modified)
    }

    override fun list(path: String): List<SmbNode> {
        reach()
        val parent = key(path)
        if (parent.isNotEmpty()) check(items[parent]?.directory == true) { "Dossier absent" }
        return items.values.filter { parentKey(it.path) == parent }
            .map { SmbNode(SmbTransfers.nameOf(it.path), it.directory, it.content.size.toLong(), it.modified) }
    }

    override fun openRead(path: String): InputStream {
        reach(); opens++
        val item = items[key(path)] ?: throw IOException("Introuvable")
        check(!item.directory)
        return ByteArrayInputStream(item.content.copyOf())
    }

    override fun createNew(path: String): OutputStream {
        reach()
        check(key(path) !in items) { "Existe déjà" }
        val parent = parentKey(path)
        check(parent.isEmpty() || items[parent]?.directory == true) { "Parent absent" }
        val item = Item(path, false)
        items[key(path)] = item
        return object : OutputStream() {
            var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                reach()
                for (i in off until off + len) {
                    failWriteAfter?.let { limit ->
                        if (written >= limit) {
                            if (unreachableAfterWriteFailure) unreachable = true
                            throw writeFailure()
                        }
                    }
                    val byte = if (corruptWrites) (b[i].toInt() xor 0x55).toByte() else b[i]
                    item.content = item.content + byte
                    written++
                }
            }
        }
    }

    override fun mkdir(path: String) {
        reach()
        check(key(path) !in items) { "Existe déjà" }
        items[key(path)] = Item(path, true)
    }

    override fun rename(from: String, to: String, replace: Boolean) {
        reach()
        val item = items[key(from)] ?: throw IOException("Introuvable")
        // Like Windows: a change of case only is a rename of the same element.
        if (key(from) != key(to)) items[key(to)]?.let { existing -> check(replace && !existing.directory) { "Destination existante" } }
        val prefix = key(from) + "\\"
        val children = items.values.filter { key(it.path).startsWith(prefix) }
        items.remove(key(from))
        children.forEach { items.remove(key(it.path)) }
        item.path = to
        items[key(to)] = item
        children.forEach { child -> child.path = to + child.path.substring(from.length); items[key(child.path)] = child }
    }

    override fun deleteFile(path: String) {
        reach()
        check(items[key(path)]?.directory == false) { "Pas un fichier" }
        items.remove(key(path))
    }

    override fun deleteEmptyDirectory(path: String) {
        reach()
        check(items[key(path)]?.directory == true) { "Pas un dossier" }
        check(items.values.none { parentKey(it.path) == key(path) }) { "Dossier non vide" }
        items.remove(key(path))
    }

    override fun deleteTree(path: String) {
        reach()
        val root = key(path)
        items.keys.filter { it == root || it.startsWith("$root\\") }.forEach { items.remove(it) }
    }
}
