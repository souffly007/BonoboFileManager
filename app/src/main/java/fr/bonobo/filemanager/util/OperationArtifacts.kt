package fr.bonobo.filemanager.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Private receipts, never replayed destructively after a restart. Paths stay on the device. */
class OperationArtifacts(private val directory: File, val id: String = UUID.randomUUID().toString()) {
    data class Entry(val path: String, val parent: String, val name: String, val directory: Boolean,
        val identity: String, val owned: Boolean, val staging: Boolean)
    private val entries = mutableListOf<Entry>()
    fun entries(): List<Entry> = entries.toList()
    fun record(entry: Entry) { entries.removeAll { it.path == entry.path }; entries += entry; save() }
    fun forget(entry: Entry) { entries.removeAll { it.path == entry.path }; save() }
    fun confirm(path: String, identity: String? = null) {
        entries.firstOrNull { it.path == path || (identity != null && it.identity == identity) }?.let {
            entries.remove(it)
            record(it.copy(path = path, owned = true))
        }
    }
    private fun save() = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "Journal des fichiers partiels inaccessible" }
        val target = File(directory, "$id.properties")
        if (entries.isEmpty()) { check(!target.exists() || target.delete()); return@synchronized }
        val temp = File.createTempFile("receipt-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                Properties().apply {
                    setProperty("count", this@OperationArtifacts.entries.size.toString())
                    this@OperationArtifacts.entries.forEachIndexed { index, e ->
                        setProperty("$index.path", e.path); setProperty("$index.parent", e.parent)
                        setProperty("$index.name", e.name); setProperty("$index.directory", e.directory.toString())
                        setProperty("$index.identity", e.identity); setProperty("$index.owned", e.owned.toString())
                        setProperty("$index.staging", e.staging.toString())
                    }
                }.store(output, null)
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
    companion object {
        private val lock = Any()
        private fun read(file: File): List<Entry> {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            return (0 until p.getProperty("count").toInt()).map { i ->
                Entry(p.getProperty("$i.path"), p.getProperty("$i.parent"), p.getProperty("$i.name"),
                    p.getProperty("$i.directory").toBooleanStrict(), p.getProperty("$i.identity"),
                    p.getProperty("$i.owned").toBooleanStrict(), p.getProperty("$i.staging").toBooleanStrict())
            }
        }
        /** Explicit acknowledgement only; never deletes or opens the recorded document. */
        fun acknowledge(directory: File, entry: Entry) = synchronized(lock) {
            (directory.listFiles() ?: error("Journal des fichiers partiels inaccessible"))
                .filter { it.extension == "properties" }.forEach { file ->
                    val stored = read(file)
                    if (entry in stored) {
                        val operation = OperationArtifacts(directory, file.nameWithoutExtension)
                        operation.entries.addAll(stored)
                        operation.forget(entry)
                    }
                }
        }
        fun pending(directory: File): List<Entry> = synchronized(lock) {
            if (!directory.exists()) return@synchronized emptyList()
            (directory.listFiles() ?: error("Journal des fichiers partiels inaccessible"))
                .filter { it.extension == "properties" }.flatMap(::read)
        }
    }
}
