package fr.bonobo.filemanager.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * Private receipts of what an operation created (partial files, folders). Paths stay on the device.
 * One file per operation: <id>.properties. Only receipts recorded here may ever be deleted automatically.
 *
 * [Entry.volume]: UUID of the volume (e.g. DC20-EA13) holding the document, when known.
 * [Entry.deferred]: the volume was absent during cleanup; the entry is deleted (and only it)
 * when this volume is mounted again — see [PendingCleanup].
 * [Entry.operation]: id of the operation that created it (also embedded in the .bonobo-part name).
 * [Entry.document]: URI returned by createDocument (SAF) or canonical path (local). Cleanup targets
 * this document, never another one found by name.
 * [Entry.bytesSent] / [Entry.modifiedAt]: updated when the streams close (success or interruption).
 * A partial whose size is ≤ bytesSent is ours (an unflushed cache after unplugging makes it smaller);
 * only a document grown or modified AFTER the interruption is refused.
 *
 * [begin] / [end]: a copy in progress writes « live=true » in its receipts BEFORE the first
 * .bonobo-part is created, and « live=false » when it ends (success, failure or cancellation, after
 * its own cleanup). A file still « live » that no running operation of this process owns was left by
 * an app that was killed (or a phone that restarted) during the transfer: see [interrupted] and
 * [PendingCleanup.adoptInterrupted].
 */
class OperationArtifacts(private val directory: File, val id: String = UUID.randomUUID().toString()) {
    data class Entry(val path: String, val parent: String, val name: String, val directory: Boolean,
        val identity: String, val owned: Boolean, val staging: Boolean,
        val volume: String? = null, val deferred: Boolean = false,
        val operation: String? = null, val document: String? = null,
        val bytesSent: Long? = null, val modifiedAt: Long? = null)
    private val entries = mutableListOf<Entry>()
    @Volatile private var live = false
    /** The operation starts: receipts written from now on say « en cours » (persisted with the first one). */
    fun begin() { live = true; if (entries().isNotEmpty()) save() }
    /** The operation is over (its own cleanup done): what remains is deferred or handed to the user. */
    fun end() { if (!live) return; live = false; if (entries().isNotEmpty()) save() }
    val isLive: Boolean get() = live
    fun entries(): List<Entry> = synchronized(entries) { entries.toList() }
    fun record(entry: Entry) { synchronized(entries) { entries.removeAll { it.path == entry.path }; entries += entry }; save() }
    /** Replace the receipt for [path] (e.g. bytes sent at stream close). */
    fun update(path: String, change: (Entry) -> Entry) {
        val updated = synchronized(entries) {
            val index = entries.indexOfFirst { it.path == path }
            if (index < 0) return
            entries[index] = change(entries[index]); true
        }
        if (updated) save()
    }
    fun forget(entry: Entry) { synchronized(entries) { entries.removeAll { it.path == entry.path } }; save() }
    fun confirm(path: String, identity: String? = null) {
        val found = synchronized(entries) {
            entries.firstOrNull { it.path == path || (identity != null && it.identity == identity) }?.also { entries.remove(it) }
        }
        found?.let { record(it.copy(path = path, owned = true)) }
    }
    private fun save() = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "Journal des fichiers partiels inaccessible" }
        val target = File(directory, "$id.properties")
        val snapshot = entries()
        if (snapshot.isEmpty()) { check(!target.exists() || target.delete()); return@synchronized }
        val temp = File.createTempFile("receipt-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                Properties().apply {
                    setProperty("count", snapshot.size.toString())
                    setProperty("live", live.toString())
                    snapshot.forEachIndexed { index, e ->
                        setProperty("$index.path", e.path); setProperty("$index.parent", e.parent)
                        setProperty("$index.name", e.name); setProperty("$index.directory", e.directory.toString())
                        setProperty("$index.identity", e.identity); setProperty("$index.owned", e.owned.toString())
                        setProperty("$index.staging", e.staging.toString())
                        e.volume?.let { setProperty("$index.volume", it) }
                        setProperty("$index.deferred", e.deferred.toString())
                        e.operation?.let { setProperty("$index.operation", it) }
                        e.document?.let { setProperty("$index.document", it) }
                        e.bytesSent?.let { setProperty("$index.bytesSent", it.toString()) }
                        e.modifiedAt?.let { setProperty("$index.modifiedAt", it.toString()) }
                    }
                }.store(output, null)
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
    companion object {
        /** Suffix of every temporary copy target: « .<nom>.bonobo-part ». */
        const val PART_SUFFIX = ".bonobo-part"
        private val lock = Any()
        /** Operations still running: their receipts must not be rewritten by another component. */
        private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun markRunning(id: String) { running += id }
        fun markFinished(id: String) { running -= id }
        fun isRunning(id: String) = id in running

        private fun read(file: File): List<Entry> {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            return (0 until p.getProperty("count").toInt()).map { i ->
                Entry(p.getProperty("$i.path"), p.getProperty("$i.parent"), p.getProperty("$i.name"),
                    p.getProperty("$i.directory").toBooleanStrict(), p.getProperty("$i.identity"),
                    p.getProperty("$i.owned").toBooleanStrict(), p.getProperty("$i.staging").toBooleanStrict(),
                    p.getProperty("$i.volume"), p.getProperty("$i.deferred", "false").toBoolean(),
                    p.getProperty("$i.operation"), p.getProperty("$i.document"),
                    p.getProperty("$i.bytesSent")?.toLongOrNull(), p.getProperty("$i.modifiedAt")?.toLongOrNull())
            }
        }
        /** Reopen a finished operation to update its receipts (cleanup after reconnection). */
        fun open(directory: File, id: String): OperationArtifacts = synchronized(lock) {
            OperationArtifacts(directory, id).also { operation ->
                val file = File(directory, "$id.properties")
                if (file.exists()) {
                    synchronized(operation.entries) { operation.entries.addAll(read(file)) }
                    operation.live = isLiveFile(file)
                }
            }
        }
        private fun isLiveFile(file: File): Boolean = runCatching {
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("live", "false").toBoolean()
        }.getOrDefault(false)
        /**
         * Operations still marked « en cours » although no operation of this process runs them: the app
         * was killed (or the phone restarted) during the transfer. Receipts of older versions have no
         * « live » flag and are never adopted automatically (they stay in « Fichiers partiels à vérifier »).
         */
        fun interrupted(directory: File): List<String> = synchronized(lock) {
            if (!directory.exists()) return@synchronized emptyList()
            (directory.listFiles() ?: error("Journal des fichiers partiels inaccessible"))
                .filter { it.extension == "properties" && !isRunning(it.nameWithoutExtension) && isLiveFile(it) }
                .map { it.nameWithoutExtension }
        }
        /** Ids of finished operations that still hold receipts. */
        fun finishedIds(directory: File): List<String> = synchronized(lock) {
            if (!directory.exists()) return@synchronized emptyList()
            (directory.listFiles() ?: error("Journal des fichiers partiels inaccessible"))
                .filter { it.extension == "properties" }.map { it.nameWithoutExtension }.filterNot(::isRunning)
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
                .filter { it.extension == "properties" && !isRunning(it.nameWithoutExtension) }.flatMap(::read)
        }
    }
}
