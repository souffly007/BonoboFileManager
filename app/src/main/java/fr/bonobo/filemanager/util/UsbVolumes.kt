package fr.bonobo.filemanager.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import fr.bonobo.filemanager.diagnostic.TechnicalJournal
import java.io.File

/**
 * Storage volumes seen by Android (internal storage, USB keys, SD cards).
 * A USB key is identified by its file-system UUID (e.g. DC20-EA13), which is also the
 * prefix of its document IDs in com.android.externalstorage.documents.
 */
object UsbVolumes {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    /** FAT32 refuses files of 4 GiB or more. */
    const val FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1

    data class Volume(val uuid: String?, val directory: File?, val removable: Boolean,
        val primary: Boolean, val mounted: Boolean, val label: String?)

    fun all(context: Context): List<Volume> = runCatching {
        context.getSystemService(StorageManager::class.java).storageVolumes.map { volume ->
            val directory = if (android.os.Build.VERSION.SDK_INT >= 30) volume.directory
                else runCatching { volume.javaClass.getMethod("getPathFile").invoke(volume) as? File }.getOrNull()
            Volume(volume.uuid, directory, volume.isRemovable, volume.isPrimary,
                volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY,
                runCatching { volume.getDescription(context) }.getOrNull())
        }
    }.getOrDefault(emptyList())

    /** Volume holding [path] (local path or externalstorage document URI); null if unknown (other providers). */
    fun forPath(context: Context, path: String): Volume? {
        val volumes = all(context)
        if (SafFiles.isSaf(path)) {
            val uri = Uri.parse(path)
            if (uri.authority != EXTERNAL_STORAGE) return null
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
            val volumeId = id.substringBefore(':')
            return if (volumeId.equals("primary", ignoreCase = true)) volumes.firstOrNull { it.primary }
                else volumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    // Unmounted keys disappear from storageVolumes: keep the UUID so the absence is known.
                    ?: Volume(volumeId, null, removable = true, primary = false, mounted = false, label = null)
        }
        val file = runCatching { File(path).absoluteFile }.getOrNull() ?: return null
        return volumes.filter { it.directory != null && file.path.startsWith(it.directory.path.trimEnd('/') + "/") || file.path == it.directory?.path }
            .maxByOrNull { it.directory!!.path.length }
            ?: Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{16})(/|$)").find(file.path)?.let {
                Volume(it.groupValues[1], null, removable = true, primary = false, mounted = false, label = null)
            }
    }

    fun isMounted(context: Context, uuid: String?): Boolean {
        if (uuid == null) return true
        return all(context).any { it.uuid.equals(uuid, ignoreCase = true) && it.mounted }
    }

    fun isUsb(volume: Volume?): Boolean = volume != null && volume.removable && !volume.primary

    /** Diagnostic classification: storage=USB for a removable key, whatever the access mode. */
    fun storage(context: Context, path: String): TechnicalJournal.Storage {
        val volume = runCatching { forPath(context, path) }.getOrNull()
        return when {
            isUsb(volume) -> TechnicalJournal.Storage.USB
            SafFiles.isSaf(path) -> TechnicalJournal.Storage.SAF
            else -> TechnicalJournal.Storage.LOCAL
        }
    }

    /** Free bytes for the volume holding [path]; null when it cannot be measured. */
    fun availableBytes(context: Context, path: String): Long? {
        forPath(context, path)?.directory?.let { directory ->
            runCatching { return android.os.StatFs(directory.path).availableBytes }
        }
        if (!SafFiles.isSaf(path)) {
            var probe: File? = File(path)
            while (probe != null && !probe.exists()) probe = probe.parentFile
            return probe?.let { runCatching { android.os.StatFs(it.path).availableBytes }.getOrNull() }
        }
        // Other document providers: Root.COLUMN_AVAILABLE_BYTES when the provider publishes it.
        return runCatching {
            val uri = Uri.parse(path)
            val rootDocument = DocumentsContract.getTreeDocumentId(uri)
            context.contentResolver.query(DocumentsContract.buildRootsUri(uri.authority),
                arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { cursor ->
                var found: Long? = null
                while (cursor.moveToNext()) {
                    val document = cursor.getString(0) ?: continue
                    if ((rootDocument == document || rootDocument.startsWith(document.trimEnd('/', ':') + ":") ||
                        rootDocument.startsWith("$document/")) && !cursor.isNull(1)) found = cursor.getLong(1)
                }
                found
            }
        }.getOrNull()
    }

    /**
     * File-system type (« vfat », « exfat », « ntfs »…) read from /proc/self/mounts, where vold mounts
     * the raw volume under /mnt/media_rw/<UUID>. Null when not visible from the app namespace.
     */
    fun fileSystem(volume: Volume?): String? {
        val uuid = volume?.uuid ?: return null
        return runCatching {
            File("/proc/self/mounts").readLines().map { it.split(' ') }.filter { it.size >= 3 }
                .firstOrNull { fields -> fields[1].endsWith("/$uuid") && fields[2] !in listOf("fuse", "sdcardfs", "esdfs") }
                ?.get(2)?.lowercase()
        }.getOrNull()
    }

    fun isFat32(fileSystem: String?): Boolean = fileSystem in listOf("vfat", "fat", "msdos", "fat32")

    // ───────────── Automatic opening when a key is plugged in ─────────────

    /** Mount time per volume UUID (ACTION_MEDIA_MOUNTED or first observation), to pick the most recent key. */
    private val mountedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val revisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** Incremented on every mount / unmount broadcast: lets the UI re-evaluate « Réessayer ». */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = revisionFlow
    fun notifyChanged() { revisionFlow.value = revisionFlow.value + 1 }

    /** Called by the ACTION_MEDIA_MOUNTED receiver (data = file:///storage/<UUID>). */
    fun markMounted(path: String?, now: Long = System.currentTimeMillis()) {
        val uuid = path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return
        mountedAt[uuid.uppercase()] = now
        notifyChanged()
    }

    fun mountTime(uuid: String?): Long? = uuid?.let { mountedAt[it.uppercase()] }

    /**
     * Choice among mounted removable volumes (pure, unit-tested):
     *  - a volume that was not mounted when the attach intent arrived (the key just plugged in);
     *  - otherwise a volume mounted recently (≤ [recentMs] before the intent): the system may have
     *    mounted it before showing the « open with » chooser;
     *  - among candidates, the most recently mounted one.
     */
    fun pickAutoOpen(mounted: List<Volume>, before: Set<String>, intentTime: Long,
        mountTimes: (String) -> Long?, recentMs: Long = 30_000): Volume? {
        val removable = mounted.filter { isUsb(it) && it.mounted && it.uuid != null && it.directory != null }
        val fresh = removable.filter { it.uuid!!.uppercase() !in before }
        val recent = removable.filter { v -> mountTimes(v.uuid!!)?.let { it >= intentTime - recentMs } == true }
        val candidates = fresh.ifEmpty { recent }
        return candidates.maxByOrNull { mountTimes(it.uuid!!) ?: Long.MIN_VALUE }
    }

    /** Result of [awaitPluggedVolume]: the volume to open, or the removable volumes already there (timeout). */
    data class Wait(val volume: Volume?, val waitedMs: Long, val fallback: Volume?)

    /**
     * USB_DEVICE_ATTACHED arrives before the volume is mounted: poll StorageManager until a
     * removable volume appears (max [timeoutMs]). On timeout, [Wait.fallback] is the most recently
     * mounted removable volume, if any (e.g. a key that was already plugged in).
     */
    suspend fun awaitPluggedVolume(context: Context, intentTime: Long, before: Set<String>,
        timeoutMs: Long = 10_000, stepMs: Long = 250): Wait {
        val started = System.currentTimeMillis()
        while (true) {
            val volumes = all(context)
            // Only volumes that appeared after the intent get an observed mount time.
            volumes.filter { isUsb(it) && it.mounted && it.uuid != null && it.uuid.uppercase() !in before }
                .forEach { mountedAt.putIfAbsent(it.uuid!!.uppercase(), System.currentTimeMillis()) }
            pickAutoOpen(volumes, before, intentTime, ::mountTime)?.let {
                notifyChanged() // USB_VOLUME_WAIT OK: « Réessayer » becomes available again
                return Wait(it, System.currentTimeMillis() - started, null)
            }
            val waited = System.currentTimeMillis() - started
            if (waited >= timeoutMs) {
                val fallback = volumes.filter { isUsb(it) && it.mounted && it.directory != null }
                    .maxByOrNull { mountTime(it.uuid) ?: Long.MIN_VALUE }
                return Wait(null, waited, fallback)
            }
            kotlinx.coroutines.delay(stepMs)
        }
    }

    /** UUIDs of removable volumes mounted right now. */
    fun mountedRemovable(context: Context): Set<String> =
        all(context).filter { isUsb(it) && it.mounted && it.uuid != null }.map { it.uuid!!.uppercase() }.toSet()
}
