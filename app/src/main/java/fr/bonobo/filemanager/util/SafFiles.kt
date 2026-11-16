package fr.bonobo.filemanager.util

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import fr.bonobo.filemanager.diagnostic.DeletionTrace


import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.DocumentsContract.Document as D
import fr.bonobo.filemanager.domain.model.FileItem
import java.io.File
import java.io.IOException
import java.util.Date
import java.security.MessageDigest
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferPhase
import fr.bonobo.filemanager.domain.transfer.ConflictAction
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.domain.transfer.TransferSkipped
import fr.bonobo.filemanager.domain.transfer.CleanCancellation
import fr.bonobo.filemanager.domain.transfer.TransferText
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** Tree document URIs remain opaque: never derive filesystem paths or document IDs from names. */
class SafFiles(private val context: Context, private val renameWithoutReplacement: Boolean = false) {
    private class ProviderUnavailableException : IOException("Le fournisseur ne répond pas")
    private val resolver = context.contentResolver
    private val parents = java.util.concurrent.ConcurrentHashMap<String, String>()
    internal val restoreJournalDirectory get() = File(context.filesDir, "trash-restore-receipts")
    private fun localVolumes(): Map<String, File> {
        val result = mutableMapOf("primary" to android.os.Environment.getExternalStorageDirectory())
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.forEach { volume ->
                if (volume.state in listOf(android.os.Environment.MEDIA_MOUNTED, android.os.Environment.MEDIA_MOUNTED_READ_ONLY)) {
                    volume.directory?.let { directory -> volume.uuid?.let { result[it] = directory } }
                }
            }
        }
        return result
    }
    fun localAlias(path: String): File? {
        val document = Uri.parse(path)
        return StorageEquivalence.local(document.authority, DC.getDocumentId(document), localVolumes())
    }
    /** UUID (null = internal) and root directory of the volume holding a local path, for [FileIdentity.local]. */
    fun volumeOf(path: String): Pair<String?, File?>? = runCatching {
        UsbVolumes.forPath(context, path)?.let { volume -> volume.uuid to volume.directory }
    }.getOrNull()
    fun localAliasAllowed(file: File): Boolean = localVolumes().values.any {
        file.canonicalFile.toPath().startsWith(it.canonicalFile.toPath()) && file.canonicalFile != it.canonicalFile
    }

    val integrityAccess = object : IntegrityVerifier.Access {
        override fun identity(path: String): String = if (isSaf(path))
            "${uri(path).authority}:${DC.getDocumentId(uri(path))}" else File(path).canonicalPath
        override fun children(path: String): List<IntegrityVerifier.Child>? {
            if (!isSaf(path)) return LocalIntegrityAccess.children(path)
            val mime = resolver.getType(uri(path)) ?: error("Document inaccessible pendant la vérification")
            return if (mime == D.MIME_TYPE_DIR) list(path).map { IntegrityVerifier.Child(it.name, it.path) } else null
        }
        override fun open(path: String): java.io.InputStream = if (isSaf(path))
            resolver.openInputStream(uri(path)) ?: error("Document illisible pendant la vérification") else LocalIntegrityAccess.open(path)
    }
    fun restoreDestinationExists(path: String): Boolean {
        if (!isSaf(path)) {
            try { java.nio.file.Files.readAttributes(File(path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS); return true }
            catch (_: java.nio.file.NoSuchFileException) {
                var parent = File(path).parentFile
                while (parent != null && !parent.exists()) parent = parent.parentFile
                check(parent?.canRead() == true) { "Destination précédente inaccessible : état indéterminé" }
                return false
            }
        }
        val target = uri(path)
        val name = target.getQueryParameter("bonobo_name")
        if (name != null) return list(target.buildUpon().clearQuery().build().toString()).any { it.name == name }
        val present = (resolver.query(target, arrayOf(D.COLUMN_DOCUMENT_ID), null, null, null)
            ?: error("Destination précédente inaccessible")).use { it.moveToFirst() }
        if (!present) list(root(path)) // require a responding tree before accepting absence
        return present
    }
    companion object {
        fun artifactDirectory(context: Context) = File(context.filesDir, "usb-operation-artifacts")
        /** « .<nom>.<tag>.bonobo-part » (tag = first 8 characters of the operation id). */
        fun partName(name: String, tag: String) = ".$name.$tag${OperationArtifacts.PART_SUFFIX}"
        fun isSaf(path: String) = path.startsWith("content://")
        fun destination(parent: String, name: String): String =
            if (isSaf(parent)) Uri.parse(parent).buildUpon().appendQueryParameter("bonobo_name", name).build().toString()
            else File(parent, name).path
        fun validateName(name: String) {
            require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Nom de fichier invalide" }
        }
        fun root(path: String): String = DC.buildDocumentUriUsingTree(Uri.parse(path), DC.getTreeDocumentId(Uri.parse(path))).toString()
    }
    private fun uri(path: String) = Uri.parse(path)
    private fun failure(e: Exception, operation: String = "COPY"): IOException {
        Diagnostic.event(Event.SAF_FAILURE, when {
            generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } -> Result.PERMISSION_DENIED
            e is java.io.FileNotFoundException -> Result.NOT_FOUND
            e is IOException -> Result.IO_ERROR
            e is IllegalStateException -> Result.PROVIDER_REJECTED
            else -> Result.ERROR
        })
        return IOException(
        if (generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException }) "Accès refusé. Sélectionnez à nouveau le dossier USB / OTG et accordez les droits nécessaires. ${e.message.orEmpty()}"
        else if (operation == "LIST" && e is ProviderUnavailableException)
            "Le dossier USB est temporairement inaccessible : le fournisseur Android ne répond pas (clé démontée ou remontée par une autre application ?). Débranchez puis rebranchez la clé, ou ouvrez-la via « USB / OTG » → « Ouvrir la clé (accès direct) »."
        else "Support inaccessible ou résultat non confirmé. Actualisez ; si nécessaire, sélectionnez à nouveau le dossier USB / OTG. ${e.message.orEmpty()}", e)
    }
    fun list(path: String): List<FileItem> {
      var phase = Phase.OPEN
      return try {
        Diagnostic.detail(Event.NAV_OPEN, Result.START, storage = Storage.SAF, kind = Kind.DIRECTORY, phase = Phase.OPEN)
        val document = uri(path)
        val hasPersistentRead = resolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri.authority == document.authority &&
                runCatching { DC.getTreeDocumentId(it.uri) == DC.getTreeDocumentId(document) }.getOrDefault(false)
        }
        Diagnostic.detail(Event.NAV_PERMISSION, if (hasPersistentRead) Result.OK else Result.NO_PERSISTED_GRANT,
            storage = Storage.SAF, kind = Kind.DIRECTORY, phase = Phase.REQUEST)
        val children = DC.buildChildDocumentsUriUsingTree(document, DC.getDocumentId(document))
        val result = mutableListOf<FileItem>()
        (resolver.query(children, arrayOf(D.COLUMN_DOCUMENT_ID, D.COLUMN_DISPLAY_NAME, D.COLUMN_MIME_TYPE, D.COLUMN_SIZE, D.COLUMN_LAST_MODIFIED), null, null, null)
            ?: throw ProviderUnavailableException()).use { cursor ->
            Diagnostic.detail(Event.NAV_OPEN, Result.OK, storage = Storage.SAF, kind = Kind.DIRECTORY, phase = Phase.OPEN)
            phase = Phase.READ
            Diagnostic.detail(Event.NAV_READ, Result.START, storage = Storage.SAF, kind = Kind.DIRECTORY, phase = Phase.READ)
            while (cursor.moveToNext()) {
                val child = DC.buildDocumentUriUsingTree(document, cursor.getString(0)).toString()
                parents[child] = path
                val mime = cursor.getString(2)
                result += FileItem(child, cursor.getString(1) ?: "Document", cursor.getLong(3), Date(cursor.getLong(4)), mime == D.MIME_TYPE_DIR, mime)
            }
        }
        Diagnostic.detail(Event.NAV_READ, Result.OK, storage = Storage.SAF, kind = Kind.DIRECTORY, phase = Phase.READ)
        result
    } catch (e: Exception) {
        val result = navigationFailure(e, path)
        Diagnostic.detail(if (phase == Phase.OPEN) Event.NAV_OPEN else Event.NAV_READ, result,
            storage = Storage.SAF, kind = Kind.DIRECTORY, phase = phase)
        Diagnostic.detail(Event.NAV_PROVIDER_FAILURE, result, storage = Storage.SAF, kind = Kind.DIRECTORY,
            phase = phase)
        throw failure(e, "LIST")
    }
    }
    private fun navigationFailure(e: Exception, path: String): Result = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } -> Result.PERMISSION_DENIED
        e is java.io.FileNotFoundException -> if (runCatching {
            val tree = root(path)
            tree != path && (resolver.query(uri(tree), arrayOf(D.COLUMN_DOCUMENT_ID), null, null, null)
                ?: return@runCatching false).use { it.moveToFirst() }
        }.getOrDefault(false)) Result.REFERENCE_INVALID else Result.NOT_FOUND
        e is ProviderUnavailableException -> Result.PROVIDER_UNAVAILABLE
        e is IOException -> Result.IO_ERROR
        else -> Result.OPEN_FAILED
    }
    private fun flags(path: String): Long = (resolver.query(uri(path), arrayOf(D.COLUMN_FLAGS), null, null, null)
        ?: throw IOException("Support déconnecté")).use { check(it.moveToFirst()) { "Support déconnecté" }; it.getLong(0) }
    private fun requireFlag(path: String, flag: Int) {
        require(flags(path) and flag.toLong() != 0L) { "Opération non autorisée : support en lecture seule ou droit non accordé" }
    }
    private val mutations = SafMutations(object : SafMutations.Access {
        override fun identity(path: String): String = integrityAccess.identity(path)
        override fun list(parent: String) = this@SafFiles.list(parent).map { SafMutations.Node(it.path, it.name, it.isDirectory) }
        override fun create(parent: String, name: String, directory: Boolean, mime: String): String {
            requireFlag(parent, D.FLAG_DIR_SUPPORTS_CREATE)
            val created = DC.createDocument(resolver, uri(parent), mime, name) ?: throw IOException("Création refusée")
            check(created.authority == uri(parent).authority) { "Création incorrecte : fournisseur inattendu ; document retourné : $created" }
            return created.toString()
        }
        override fun delete(path: String): Boolean {
            requireFlag(path, D.FLAG_SUPPORTS_DELETE)
            return DC.deleteDocument(resolver, uri(path))
        }
        override fun rename(path: String, name: String): String? {
            requireFlag(path, D.FLAG_SUPPORTS_RENAME)
            return DC.renameDocument(resolver, uri(path), name)?.toString()
        }
    })
    private val replacements = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun currentReference(path: String): String {
        var current = path
        val seen = mutableSetOf<String>()
        while (replacements.containsKey(current)) {
            check(seen.add(current)) { "Référence de document ambiguë : actualisez le dossier" }
            current = replacements.getValue(current)
        }
        return current
    }
    private fun createTracked(parent: String, name: String, mime: String,
        operation: OperationArtifacts? = null, staging: Boolean = false, volume: String? = null,
        storage: Storage = Storage.SAF, diagnostic: java.util.UUID? = null, mode: Mode = Mode.UNKNOWN): String {
        validateName(name)
        val intent = OperationArtifacts.Entry(destination(parent, name), parent, name, mime == D.MIME_TYPE_DIR,
            "unconfirmed:${java.util.UUID.randomUUID()}", false, staging, volume)
        val created = mutations.create(parent, name, mime == D.MIME_TYPE_DIR, mime, storage, diagnostic, mode,
            beforeCreate = { operation?.record(intent) }) { node ->
            parents[node.path] = parent
            // node.path is the URI RETURNED by createDocument: cleanup targets this document only.
            operation?.record(OperationArtifacts.Entry(node.path, parent, name, node.directory,
                integrityAccess.identity(node.path), false, staging, volume,
                operation = operation.id, document = node.path))
            operation?.forget(intent)
        }
        parents[created] = parent
        operation?.confirm(created, integrityAccess.identity(created))
        return created
    }
    fun create(parent: String, name: String, mime: String): String {
        val operation = OperationArtifacts(artifactDirectory(context))
        return try {
            val created = createTracked(parent, name, mime, operation)
            operation.entries().forEach(operation::forget)
            created
        } catch (e: Exception) {
            throw failure(IOException("Création non confirmée ; dossier : $parent ; document(s) à examiner : " +
                operation.entries().joinToString { it.path } + ". ${e.message.orEmpty()}", e), "CREATE")
        }
    }
    fun requireDelete(path: String) = requireFlag(path, D.FLAG_SUPPORTS_DELETE)
    fun deletionResult(path: String, trace: DeletionTrace? = null): SafMutations.DeleteResult {
        try { resolveParent(path) } catch (e: Exception) {
            trace?.verify(Result.INDETERMINATE)
            return SafMutations.DeleteResult(SafMutations.Deletion.INDETERMINATE, e)
        }
        val parent = parents[path] ?: return SafMutations.DeleteResult(SafMutations.Deletion.INDETERMINATE,
            IOException("Parent inconnu : actualisez le dossier avant de supprimer")).also { trace?.verify(Result.INDETERMINATE) }
        return mutations.delete(path, parent, trace).also {
            if (it.state == SafMutations.Deletion.CONFIRMED) {
                parents.remove(path)
                parents.entries.removeAll { entry -> entry.value == path }
            }
        }
    }
    fun delete(path: String, mode: Mode = Mode.PERMANENT) {
        val type = runCatching { resolver.getType(uri(path)) }.getOrNull()
        val kind = when (type) { D.MIME_TYPE_DIR -> Kind.DIRECTORY; null -> Kind.UNKNOWN; else -> Kind.FILE }
        val trace = DeletionTrace(if (UsbVolumes.storage(context, path) == Storage.USB) Storage.USB else Storage.SAF, kind, mode)
        trace.start()
        val result = try { deletionResult(path, trace) } catch (e: Exception) {
            trace.verify(Result.INDETERMINATE); trace.end(Result.INDETERMINATE); throw e
        }
        trace.end(when (result.state) {
            SafMutations.Deletion.CONFIRMED -> Result.OK
            SafMutations.Deletion.REFUSED -> Result.REFUSED
            SafMutations.Deletion.INDETERMINATE -> Result.INDETERMINATE
        })
        if (result.state != SafMutations.Deletion.CONFIRMED) throw IOException(
            when (result.state) {
                SafMutations.Deletion.REFUSED -> "Suppression refusée"
                else -> "Suppression indéterminée : disparition non confirmée"
            } + " : $path ; dossier : ${parents[path].orEmpty()}", result.cause)
    }
    fun rename(path: String, name: String): String {
        Diagnostic.event(Event.RENAME, if (renameWithoutReplacement) Result.START else Result.REFUSED)
        validateName(name)
        val current = currentReference(path)
        val parent = parents[current] ?: throw IOException("Actualisez le dossier avant de renommer")
        // Generic SAF has no atomic no-replace rename. Only providers with an independently
        // established contract may use the rename adapter; never infer it from the RENAME flag.
        check(renameWithoutReplacement) {
            "Renommage indisponible pour cet emplacement USB : Android ne garantit pas qu'un fichier existant ne sera pas remplacé. Le document est conservé."
        }
        return mutations.rename(current, parent, name) { updated ->
            check(uri(updated).authority == uri(current).authority) { "Fournisseur de renommage inattendu" }
            if (updated != current) {
                replacements.remove(updated)
                replacements.entries.forEach { if (it.value == current) replacements[it.key] = updated }
                replacements[current] = updated
                parents.remove(current)
                parents.entries.removeAll { it.value == current }
            }
            parents[updated] = parent
        }
    }
    private fun containsDirectory(source: String, destination: String): Boolean {
        val pending = java.util.ArrayDeque<String>().apply { add(source) }
        val visited = mutableSetOf<String>()
        val targetId = DC.getDocumentId(uri(destination))
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            if (!visited.add(DC.getDocumentId(uri(path)))) continue
            for (child in list(path).filter { it.isDirectory }) {
                if (DC.getDocumentId(uri(child.path)) == targetId) return true
                pending.add(child.path)
            }
        }
        return false
    }

    // ───────────────────────────── Copy / move ─────────────────────────────
    //
    // Atomic write: every file is written as « .<nom>.bonobo-part », streams are closed, the
    // SHA-256 of the written document is verified, and only then the document is renamed to its
    // final name. An interruption (cancel, key removed, key full) therefore never leaves a partial
    // file under the final name. Every created document is recorded (OperationArtifacts) so that
    // cleanup deletes only what this operation created — immediately, or when the key comes back.

    /** Conflict policy for the root element and, for folders, for files met while merging. */
    /** @param spaceChecked free space was already checked by the caller (batch preflight, trash, restore). */
    data class CopyPolicy(val conflict: ConflictAction = ConflictAction.ASK, val compareHash: Boolean = false,
        val spaceChecked: Boolean = false)

    /** Outcome of one root copy. [hashes]: SHA-256 per source file, used before deleting a moved source. */
    data class CopyResult(val path: String, val copied: Int, val identical: Int, val renamed: Int,
        val replaced: Int, val hashes: Map<String, ByteArray>, val diagnosticOperation: java.util.UUID? = null)

    private inner class CopyRun(val policy: CopyPolicy, val moving: Boolean, val volume: UsbVolumes.Volume?,
        val storage: Storage) {
        val artifacts = OperationArtifacts(artifactDirectory(context))
        /** Same id in the journal (op=), the receipts and the .bonobo-part name. */
        val op: java.util.UUID? = Diagnostic.operation()?.let { java.util.UUID.fromString(artifacts.id) }
        val tag: String get() = artifacts.id.take(8)
        /** File key of local parts at creation: checked once, when the output is opened. */
        val localKeys = mutableMapOf<String, Any>()
        val mode = if (moving) Mode.MOVE else Mode.COPY
        val usb get() = UsbVolumes.isUsb(volume)
        var copied = 0; var identical = 0; var renamed = 0; var replaced = 0
        val hashes = mutableMapOf<String, ByteArray>()
        /** Identities of the files published (verified, final name) by this run. */
        val published = mutableSetOf<String>()
    }

    /** [keptFiles]: complete, verified files left in the kept folders. */
    private class FailureCleanup(val report: ArtifactCleanup.Report, val keptFolders: Int, val keptFiles: Int = 0)

    suspend fun move(source: String, target: String, policy: CopyPolicy = CopyPolicy()) {
        if (isSaf(source)) requireDelete(source)
        val result = copyWithReport(source, target, policy, moving = true)
        // Each file was verified right after being written. Before deleting the source we only
        // re-read the source once, to make sure it did not change during the copy.
        VerifiedMove.finish(result.path, retainSource = false,
            verify = { verifySourceUnchanged(source, result) }, delete = {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                currentCoroutineContext().ensureActive()
                if (isSaf(source)) {
                    resolveParent(source)
                    delete(source)
                } else {
                    val file = File(source)
                    val trace = DeletionTrace(UsbVolumes.storage(context, source),
                        if (file.isDirectory) Kind.DIRECTORY else Kind.FILE, Mode.PERMANENT)
                    trace.start()
                    try {
                        val accepted = FileUtils.deleteRecursively(file)
                        trace.provider(if (accepted) Result.OK else Result.REFUSED)
                        val absent = !SafeFiles.exists(file)
                        trace.verify(if (absent) Result.OK else Result.INDETERMINATE)
                        trace.end(if (accepted && absent) Result.OK else Result.INDETERMINATE)
                        check(accepted && absent) { "Suppression source non confirmée" }
                    } catch (e: Exception) { trace.end(Result.INDETERMINATE); throw e }
                }
            })
    }

    suspend fun copy(source: String, target: String, policy: CopyPolicy = CopyPolicy()): String =
        copyWithReport(source, target, policy, moving = false).path

    suspend fun copyWithReport(source: String, target: String, policy: CopyPolicy, moving: Boolean): CopyResult {
        val parent = parentOf(target)
        val name = nameOf(target)
        val volume = runCatching { UsbVolumes.forPath(context, parent) }.getOrNull()
        // storage=USB as soon as a key is involved: destination, or source of a move (e.g. USB → corbeille).
        val sourceOnUsb = moving && UsbVolumes.storage(context, source) == Storage.USB
        val run = CopyRun(policy, moving, volume, if (UsbVolumes.isUsb(volume) || sourceOnUsb) Storage.USB
            else if (isSaf(parent)) Storage.SAF else Storage.LOCAL)
        OperationArtifacts.markRunning(run.artifacts.id)
        // « En cours » is written with the first receipt, i.e. BEFORE the first .bonobo-part exists:
        // if the app is killed, the next start finds and cleans it (PendingCleanup.adoptInterrupted).
        run.artifacts.begin()
        try {
            val published = copyRoot(source, parent, name, run)
            // Success: receipts of created folders are no longer needed.
            run.artifacts.entries().filter { !it.staging }.forEach(run.artifacts::forget)
            if (run.artifacts.entries().isNotEmpty())
                ArtifactCleanup.execute(run.artifacts, cleanupAccess(), diagnosticOperation = run.op, storage = run.storage)
            summary(run)?.let { TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING, note = it)) }
            return CopyResult(published, run.copied, run.identical, run.renamed, run.replaced, run.hashes.toMap(), run.op)
        } catch (e: TransferSkipped) {
            throw e
        } catch (e: Exception) {
            throw failureAfterCleanup(e, run)
        } finally {
            // After our own cleanup: what is left is deferred (key absent) or handed to the user.
            runCatching { run.artifacts.end() }
            OperationArtifacts.markFinished(run.artifacts.id)
        }
    }

    /** Bytes (null if a size is unknown) and file count of the source, from the snapshot. */
    private fun need(source: String, snapshot: Map<String, List<FileItem>>, directory: Boolean): Pair<Long?, Long> {
        if (!directory) return sourceSize(source) to 1L
        val files = snapshot.values.flatten().filter { !it.isDirectory }
        if (files.any { it.size < 0 }) return null to files.size.toLong()
        return files.sumOf { it.size } to files.size.toLong()
    }

    /**
     * SPACE_CHECK for a copy about to start elsewhere (trash restoration: before the receipt is written).
     * [target]: final destination (local path, or SAF parent + « bonobo_name »).
     */
    suspend fun requireSpace(source: String, target: String, restore: Boolean = false) {
        val (snapshot, _) = snapshot(source)
        val directory = isDirectory(source)
        val (bytes, files) = need(source, snapshot, directory)
        val parent = parentOf(target)
        if (bytes != null) SpaceCheck.require(context, parent, bytes, files, Diagnostic.operation(),
            if (restore) Mode.RESTORE else Mode.COPY)
    }

    private fun summary(run: CopyRun): String? = listOfNotNull(
        run.identical.takeIf { it > 0 }?.let { TransferText.plural(it, "fichier identique ignoré", "fichiers identiques ignorés") },
        run.renamed.takeIf { it > 0 }?.let { TransferText.plural(it, "élément renommé", "éléments renommés") + " (« nom (1) »)" },
        run.replaced.takeIf { it > 0 }?.let { TransferText.plural(it, "fichier remplacé", "fichiers remplacés") }
    ).joinToString(" · ").ifEmpty { null }

    /** Cleanup first (never cancellable), then a typed, French failure for the result card. */
    private suspend fun failureAfterCleanup(e: Exception, run: CopyRun): Exception {
        val cancelled = e is kotlinx.coroutines.CancellationException
        val kind = when {
            cancelled -> null
            e is TransferFailure -> e.kind
            CopyFailures.isNoSpace(e) -> FailureKind.DISK_FULL
            CopyFailures.isFileTooLarge(e) -> FailureKind.FILE_TOO_LARGE
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { volumeLost(e, run) } -> FailureKind.USB_REMOVED
            else -> FailureKind.OTHER
        }
        when (kind) {
            null -> Diagnostic.detail(Event.CANCEL, Result.CANCELLED, run.op, run.storage, mode = run.mode)
            FailureKind.DISK_FULL -> Diagnostic.detail(Event.DISK_FULL, Result.INSUFFICIENT_SPACE, run.op, run.storage, mode = run.mode)
            FailureKind.FILE_TOO_LARGE -> Diagnostic.detail(Event.FILE_TOO_LARGE, Result.REFUSED, run.op, run.storage, mode = run.mode)
            FailureKind.USB_REMOVED -> Diagnostic.detail(Event.USB_REMOVED_DURING_COPY, Result.VOLUME_ABSENT, run.op, run.storage, mode = run.mode)
            FailureKind.CONFLICT -> Unit // DESTINATION_CONFLICT already logged
            else -> Diagnostic.detail(Event.SAF_FAILURE, classify(e), run.op, run.storage, mode = run.mode)
        }
        val cleanup = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cleanupAfterFailure(run) }
        val detail = describe(cleanup, if (cancelled) run else null)
        val technical = if (e is TransferFailure) e.technical else CopyFailures.technical(e).ifBlank { null }
        if (cancelled) {
            return if (cleanup.report.overall == ArtifactCleanup.Outcome.DELETED)
                CleanCancellation(detail, if (cleanup.keptFolders > 0) cleanup.keptFiles else 0)
                else kotlinx.coroutines.CancellationException(detail).also { it.initCause(e) }
        }
        val message = when (kind) {
            FailureKind.DISK_FULL -> "Plus d’espace libre pendant l’écriture."
            FailureKind.FILE_TOO_LARGE -> "Fichier trop volumineux pour ce support (limite FAT32 : 4 Go par fichier)."
            FailureKind.USB_REMOVED -> "Clé USB retirée pendant l’écriture."
            else -> userMessage(e)
        }
        return TransferFailure(kind ?: FailureKind.OTHER, "$message $detail Source conservée.", technical, run.usb, e)
    }

    private fun classify(e: Exception): Result = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } -> Result.PERMISSION_DENIED
        e is java.io.FileNotFoundException -> Result.NOT_FOUND
        e is IOException -> Result.IO_ERROR
        e is IllegalStateException -> Result.PROVIDER_REJECTED
        else -> Result.ERROR
    }

    /** Short French text; raw system text (often English) only goes to the technical details. */
    private fun userMessage(e: Exception): String = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } ->
            "Accès refusé. Sélectionnez à nouveau le dossier USB / OTG et accordez les droits nécessaires."
        e is TransferFailure || e is IllegalStateException || e is IllegalArgumentException ->
            e.message?.takeIf { it.isNotBlank() } ?: "Copie non confirmée."
        else -> "Erreur de lecture ou d’écriture sur le support."
    }

    /** The key really disappeared (unmount may lag a few hundred ms behind the I/O error). */
    private suspend fun volumeLost(e: Exception, run: CopyRun): Boolean {
        val uuid = run.volume?.uuid ?: return false
        if (!UsbVolumes.isMounted(context, uuid)) return true
        if (!CopyFailures.looksLikeDeviceLoss(e)) return false
        repeat(5) {
            kotlinx.coroutines.delay(300)
            if (!UsbVolumes.isMounted(context, uuid)) return true
        }
        return false
    }

    private fun cleanupAfterFailure(run: CopyRun): FailureCleanup {
        if (run.artifacts.entries().isEmpty()) return FailureCleanup(ArtifactCleanup.Report(emptyMap()), 0)
        // A folder created by this copy that still holds verified files (and no .bonobo-part) is the
        // expected result of a partial copy, not a leftover (KEPT_VERIFIED): « Réessayer » merges into it.
        val report = ArtifactCleanup.execute(run.artifacts, cleanupAccess(), diagnosticOperation = run.op, storage = run.storage,
            verified = { node -> node.identity in run.published })
        val kept = report.outcomes.filterValues { it == ArtifactCleanup.Outcome.KEPT }.keys
        // Volume absent: keep the receipts; they are deleted (and only them) when the key comes back.
        val deferred = report.outcomes.filterValues { it == ArtifactCleanup.Outcome.UNKNOWN }.keys
        deferred.forEach { run.artifacts.record(it.copy(deferred = true)) }
        if (deferred.isNotEmpty()) Diagnostic.detail(Event.PENDING_CLEANUP, Result.START, run.op, run.storage,
            mode = Mode.CLEANUP, expected = deferred.size.toLong())
        return FailureCleanup(ArtifactCleanup.Report(report.outcomes.filterKeys { it !in kept }), kept.size, run.copied)
    }

    /** User-facing, never claims a confirmed cleanup without DELETED. */
    private fun describe(cleanup: FailureCleanup, cancelled: CopyRun? = null): String {
        val report = cleanup.report
        val files = TransferText.plural(cleanup.keptFiles, "fichier complet conservé", "fichiers complets conservés")
        val kept = if (cleanup.keptFolders > 0) " $files dans le dossier." else ""
        if (cancelled != null && cleanup.keptFolders > 0 && report.overall == ArtifactCleanup.Outcome.DELETED)
            return "${if (cancelled.moving) "Déplacement annulé" else "Copie annulée"} : $files dans le dossier."
        if (report.outcomes.isEmpty()) return "Aucun fichier partiel.$kept"
        val parts = report.outcomes.keys.count { !it.directory }
        return when (report.overall) {
            ArtifactCleanup.Outcome.DELETED, ArtifactCleanup.Outcome.KEPT ->
                (if (parts > 0) TransferText.plural(parts, "fichier partiel supprimé", "fichiers partiels supprimés") + "."
                else "Dossier vide supprimé.") + kept
            ArtifactCleanup.Outcome.UNKNOWN ->
                "Clé inaccessible : le fichier partiel sera supprimé automatiquement au rebranchement.$kept"
            ArtifactCleanup.Outcome.LEFTOVER ->
                "Un fichier partiel n’a pas pu être supprimé : voir « Fichiers partiels à vérifier ».$kept"
        }
    }

    internal fun cleanupAccess(): ArtifactCleanup.Access = object : ArtifactCleanup.Access {
        // Emptiness is checked just before deletion; only folders created by the operation are recorded.
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
        override fun available(entry: OperationArtifacts.Entry): Boolean {
            if (!UsbVolumes.isMounted(context, entry.volume)) return false
            return if (isSaf(entry.parent)) runCatching {
                (resolver.query(uri(entry.parent), arrayOf(D.COLUMN_DOCUMENT_ID), null, null, null) ?: return false)
                    .use { it.moveToFirst() }
            }.getOrDefault(false)
            else File(entry.parent).isDirectory && File(entry.parent).list() != null
        }
        override fun list(parent: String): List<ArtifactCleanup.Node> = if (isSaf(parent))
            this@SafFiles.list(parent).map { ArtifactCleanup.Node(it.path, it.name, it.isDirectory,
                integrityAccess.identity(it.path), it.size.takeIf { s -> s >= 0 }, it.lastModified.time.takeIf { t -> t > 0 }) }
        else (File(parent).listFiles() ?: throw IOException("Dossier de nettoyage inaccessible")).map {
            ArtifactCleanup.Node(it.path, it.name, it.isDirectory, localIdentity(it.path),
                if (it.isFile) it.length() else null, it.lastModified().takeIf { t -> t > 0 })
        }
        // SAF: the document id returned by createDocument (stable across remount on externalstorage).
        // Local: the canonical path. Old receipts (fileKey:mtime identity) are matched by path too.
        override fun matches(entry: OperationArtifacts.Entry, node: ArtifactCleanup.Node): Boolean =
            if (isSaf(node.path)) node.identity == entry.identity ||
                (entry.document != null && runCatching { integrityAccess.identity(entry.document) == node.identity }.getOrDefault(false))
            else runCatching { File(node.path).canonicalPath == File(entry.document ?: entry.path).canonicalPath }.getOrDefault(false)
        override fun delete(entry: OperationArtifacts.Entry) {
            if (isSaf(entry.path)) {
                parents[entry.path] = entry.parent
                this@SafFiles.delete(entry.path, Mode.CLEANUP)
            } else java.nio.file.Files.delete(File(entry.path).toPath()) // refuses a non-empty folder
        }
    }

    /**
     * Receipt identity of a local document: its canonical path. The former « fileKey:creationTime »
     * was frozen at creation, but on Android creationTime() returns the MODIFICATION time and FUSE/vfat
     * renumbers inodes after a remount: every written partial then looked foreign (IDENTITY_MISMATCH).
     */
    private fun localIdentity(path: String): String {
        val attrs = java.nio.file.Files.readAttributes(File(path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(!attrs.isSymbolicLink) { "Lien symbolique refusé" }
        return File(path).canonicalPath
    }
    private fun localFileKey(path: String): Any? = runCatching {
        java.nio.file.Files.readAttributes(File(path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
    }.getOrNull()

    /** Size and date actually stored, read after the streams are closed. */
    private fun observe(path: String): Pair<Long?, Long?> = runCatching {
        if (isSaf(path)) (resolver.query(uri(path), arrayOf(D.COLUMN_SIZE, D.COLUMN_LAST_MODIFIED), null, null, null) ?: return null to null).use {
            if (!it.moveToFirst()) null to null
            else (if (it.isNull(0)) null else it.getLong(0)) to (if (it.isNull(1)) null else it.getLong(1).takeIf { t -> t > 0 })
        } else File(path).let { f -> (if (f.isFile) f.length() else null) to f.lastModified().takeIf { it > 0 } }
    }.getOrDefault(null to null)

    private fun resolveParent(path: String) {
        if (parents.containsKey(path)) return
        val ids = DC.findDocumentPath(resolver, uri(path))?.path ?: return
        if (ids.size < 2) return
        val parent = DC.buildDocumentUriUsingTree(uri(path), ids[ids.lastIndex - 1]).toString()
        check(list(parent).any { integrityAccess.identity(it.path) == integrityAccess.identity(path) }) {
            "Parent du document non confirmé"
        }
        parents[path] = parent
    }

    private fun parentOf(target: String): String = if (isSaf(target)) uri(target).buildUpon().clearQuery().build().toString()
        else File(target).parent ?: error("Destination invalide")
    private fun nameOf(target: String): String = (if (isSaf(target)) uri(target).getQueryParameter("bonobo_name")
        else File(target).name) ?: error("Destination invalide")
    private fun isDirectory(path: String): Boolean = if (isSaf(path)) resolver.getType(uri(path)) == D.MIME_TYPE_DIR
        else File(path).isDirectory
    private fun children(path: String): List<FileItem> = if (isSaf(path)) list(path) else
        (File(path).listFiles() ?: throw IOException("Dossier inaccessible")).map { with(FileUtils) { it.toFileItem() } }
    private fun sourceSize(path: String): Long? = if (isSaf(path)) resolver.query(uri(path), arrayOf(D.COLUMN_SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n >= 0 } else null
    } else File(path).length()

    /** Snapshot before creating anything: opaque providers may alias local storage. */
    private suspend fun snapshot(source: String): Pair<Map<String, List<FileItem>>, Set<String>> {
        val snapshot = mutableMapOf<String, List<FileItem>>()
        val visited = mutableSetOf<String>()
        suspend fun collect(path: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= fr.bonobo.filemanager.domain.transfer.TransferInventory.MAX_DEPTH) { "Arborescence trop profonde" }
            if (!isSaf(path)) require(!java.nio.file.Files.isSymbolicLink(File(path).toPath())) { "Copie de lien symbolique non autorisée" }
            val directory = if (isSaf(path)) (resolver.getType(uri(path)) ?: error("Source SAF inaccessible")) == D.MIME_TYPE_DIR
                else File(path).isDirectory
            if (!directory) return
            require(visited.add(integrityAccess.identity(path))) { "Cycle dans la source" }
            val children = children(path)
            snapshot[path] = children
            children.forEach { collect(it.path, depth + 1) }
        }
        collect(source, 0)
        return snapshot to visited
    }

    /** An unresolvable cross-provider alias must not make our creation a member of the source tree. */
    private suspend fun aliasCheck(snapshot: Map<String, List<FileItem>>) {
        for ((path, originalChildren) in snapshot) {
            currentCoroutineContext().ensureActive()
            val now = integrityAccess.children(path) ?: error("Type source modifié")
            val unchanged = now.map { it.name to it.path }.toSet() == originalChildren.map { it.name to it.path }.toSet()
            check(unchanged) { "Source modifiée ou destination alias de la source : copie refusée" }
        }
    }

    private fun logConflict(run: CopyRun, directory: Boolean, result: Result) {
        Diagnostic.detail(if (result == Result.CONFLICT) Event.DESTINATION_CONFLICT else Event.CONFLICT_RESOLVED, result,
            run.op, run.storage, if (directory) Kind.DIRECTORY else Kind.FILE, run.mode, Phase.CREATE)
    }

    private suspend fun copyRoot(source: String, parent: String, name: String, run: CopyRun): String {
        currentCoroutineContext().ensureActive()
        validateName(name)
        if (!isSaf(source)) require(File(source).exists()) { "Source inaccessible" }
        val (snapshot, visited) = snapshot(source)
        require(integrityAccess.identity(parent) !in visited) { "Destination dans la source : transfert refusé" }
        val directory = isDirectory(source)
        if (directory && !isSaf(source) && !isSaf(parent)) {
            require(!File(parent, name).canonicalFile.toPath().startsWith(File(source).canonicalFile.toPath())) { "Copie d'un dossier dans lui-même interdite" }
        }
        if (directory && isSaf(source) && isSaf(parent) && uri(source).authority == uri(parent).authority) {
            require(DC.getDocumentId(uri(source)) != DC.getDocumentId(uri(parent))) { "Copie d'un dossier dans lui-même interdite" }
            val descendant = try {
                if (android.os.Build.VERSION.SDK_INT >= 29) DC.isChildDocument(resolver, uri(source), uri(parent))
                else containsDirectory(source, parent)
            } catch (_: UnsupportedOperationException) { containsDirectory(source, parent) }
            require(!descendant) { "Copie d'un dossier dans un descendant interdite" }
        }
        val existing = children(parent).firstOrNull { it.name == name }
        var finalName = name
        var mergeInto: String? = null
        var replace: FileItem? = null
        if (existing != null) {
            logConflict(run, directory, Result.CONFLICT)
            val same = runCatching { integrityAccess.identity(existing.path) == integrityAccess.identity(source) }.getOrDefault(false)
            when (run.policy.conflict) {
                ConflictAction.ASK -> throw TransferFailure(FailureKind.CONFLICT,
                    "« $name » existe déjà dans la destination. Choisissez Remplacer, Renommer, Ignorer" +
                        (if (directory) " ou Fusionner." else "."), usb = run.usb)
                ConflictAction.SKIP -> {
                    logConflict(run, directory, Result.SKIPPED)
                    throw TransferSkipped("Ignoré : « $name » existe déjà dans la destination.")
                }
                ConflictAction.RENAME -> {
                    finalName = uniqueName(parent, name, directory)
                    run.renamed++
                    logConflict(run, directory, Result.RENAMED)
                }
                ConflictAction.MERGE, ConflictAction.REPLACE -> {
                    if (same) throw TransferFailure(FailureKind.CONFLICT,
                        "La source et la destination sont le même élément : rien à ${if (run.moving) "déplacer" else "copier"}. Choisissez Renommer.", usb = run.usb)
                    if (existing.isDirectory != directory) throw TransferFailure(FailureKind.CONFLICT,
                        "« $name » existe déjà mais n’est pas du même type (fichier / dossier). Choisissez Renommer ou Ignorer.", usb = run.usb)
                    when {
                        directory -> { mergeInto = existing.path; logConflict(run, true, Result.MERGED) }
                        run.policy.conflict == ConflictAction.REPLACE -> { replace = existing; logConflict(run, false, Result.REPLACED) }
                        identical(source, existing, run) -> {
                            run.identical++
                            logConflict(run, false, Result.SKIPPED)
                            throw TransferSkipped("Identique au fichier déjà présent : ignoré.")
                        }
                        else -> { finalName = uniqueName(parent, name, false); run.renamed++; logConflict(run, false, Result.RENAMED) }
                    }
                }
            }
        }
        // Free space before the first byte (a merge needs an unknown part of it: DISK_FULL detection applies).
        if (!run.policy.spaceChecked && mergeInto == null) {
            val (bytes, files) = need(source, snapshot, directory)
            if (bytes != null) SpaceCheck.require(context, parent, bytes, files, run.op, run.mode,
                replacing = replace != null, storage = run.storage)
        }
        return if (directory) copyDirectory(source, parent, finalName, snapshot, run, mergeInto, root = true)
            else copyFile(source, parent, finalName, run, replace, snapshot, root = true)
    }

    private suspend fun copyDirectory(source: String, parent: String, name: String, snapshot: Map<String, List<FileItem>>,
        run: CopyRun, mergeInto: String?, root: Boolean): String {
        currentCoroutineContext().ensureActive()
        val target = mergeInto ?: createDirectory(parent, name, run)
        if (root && mergeInto == null) aliasCheck(snapshot)
        val sourceChildren = snapshot[source] ?: error("Inventaire source absent")
        val present = if (mergeInto != null) children(target).associateBy { it.name } else emptyMap()
        val placed = mutableSetOf<String>()
        for (child in sourceChildren) {
            currentCoroutineContext().ensureActive()
            val existing = present[child.name]
            when {
                existing == null -> copyChild(child, target, child.name, snapshot, run, null, null)
                existing.isDirectory && child.isDirectory -> copyChild(child, target, child.name, snapshot, run, existing.path, null)
                existing.isDirectory != child.isDirectory -> {
                    run.renamed++; logConflict(run, child.isDirectory, Result.RENAMED)
                    val renamed = uniqueName(target, child.name, child.isDirectory)
                    copyChild(child, target, renamed, snapshot, run, null, null)
                    placed += renamed
                }
                identical(child.path, existing, run) -> {
                    run.identical++
                    val observer = TransferEvents.observer()
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, child.path, child.name, fileBytes = child.size,
                        fileTotal = child.size, fileStart = true))
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = child.size, fileBytes = child.size))
                }
                run.policy.conflict == ConflictAction.REPLACE -> {
                    logConflict(run, false, Result.REPLACED)
                    copyChild(child, target, child.name, snapshot, run, null, existing)
                }
                else -> {
                    run.renamed++; logConflict(run, false, Result.RENAMED)
                    val renamed = uniqueName(target, child.name, false)
                    copyChild(child, target, renamed, snapshot, run, null, null)
                    placed += renamed
                }
            }
            placed += child.name
        }
        // Structural check only (names): contents were verified file by file right after writing.
        val actualNames = children(target).map { it.name }.toSet()
        check(actualNames.containsAll(placed)) { "Dossier copié non confirmé" }
        return target
    }

    private suspend fun copyChild(child: FileItem, parent: String, name: String, snapshot: Map<String, List<FileItem>>,
        run: CopyRun, mergeInto: String?, replace: FileItem?): String =
        if (child.isDirectory) copyDirectory(child.path, parent, name, snapshot, run, mergeInto, root = false)
        else copyFile(child.path, parent, name, run, replace, snapshot, root = false)

    /** Same size; with « comparer le contenu » (always for a move) the SHA-256 must match too. */
    private suspend fun identical(source: String, existing: FileItem, run: CopyRun): Boolean {
        if (existing.isDirectory) return false
        val size = sourceSize(source) ?: return false
        if (existing.size != size) return false
        if (!run.policy.compareHash && !run.moving) return true
        val expected = sha256(source)
        val matches = sha256(existing.path).contentEquals(expected)
        Diagnostic.event(Event.SHA256, if (matches) Result.MATCH else Result.MISMATCH, run.op, size)
        if (matches) run.hashes[source] = expected
        return matches
    }

    private suspend fun sha256(path: String, progress: ((Long) -> Unit)? = null): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        integrityAccess.open(path).use { stream ->
            val buffer = ByteArray(64 * 1024)
            val throttle = VerifiedStreamCopy.ProgressThrottle(sourceSize(path), progress)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                throttle.add(count.toLong())
            }
        }
        return digest.digest()
    }

    /** « nom (1).ext », « nom (2).ext »… (no extension split for folders). */
    private fun uniqueName(parent: String, name: String, directory: Boolean): String {
        val taken = children(parent).map { it.name }.toSet()
        val dot = if (directory) -1 else name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (index in 1..9999) {
            val candidate = "$stem ($index)$extension"
            if (candidate !in taken) return candidate
        }
        error("Aucun nom libre pour « $name »")
    }

    /**
     * « .<nom>.<8 premiers caractères de l’op>.bonobo-part »: unique per operation, so a stale part of a
     * previous attempt (e.g. awaiting reconnection) never collides with, nor is mistaken for, this one.
     */
    private fun partName(parent: String, name: String, run: CopyRun): String {
        val base = if (name.length > 220) name.take(190) + "~" + Integer.toHexString(name.hashCode()) else name
        val taken = children(parent).map { it.name }.toSet()
        var candidate = partName(base, run.tag)
        var index = 2
        // Same file name twice in one operation (cannot happen in one folder) — still never reuse.
        while (candidate in taken) { candidate = ".$base.${run.tag}-$index${OperationArtifacts.PART_SUFFIX}"; index++ }
        return candidate
    }

    private fun createDirectory(parent: String, name: String, run: CopyRun): String = if (isSaf(parent)) {
        createTracked(parent, name, D.MIME_TYPE_DIR, run.artifacts, staging = false, volume = run.volume?.uuid,
            storage = run.storage, diagnostic = run.op, mode = run.mode)
    } else createLocal(parent, name, directory = true, staging = false, run = run)

    private fun createLocal(parent: String, name: String, directory: Boolean, staging: Boolean, run: CopyRun): String {
        validateName(name)
        val file = File(parent, name)
        if (SafeFiles.exists(file)) {
            Diagnostic.detail(Event.DESTINATION_CONFLICT, Result.CONFLICT, run.op, run.storage,
                if (directory) Kind.DIRECTORY else Kind.FILE, run.mode, Phase.CREATE)
            throw TransferFailure(FailureKind.CONFLICT, "« $name » est apparu dans la destination pendant la copie.", usb = run.usb)
        }
        val intent = OperationArtifacts.Entry(file.path, parent, name, directory,
            "unconfirmed:${java.util.UUID.randomUUID()}", false, staging, run.volume?.uuid)
        run.artifacts.record(intent)
        if (directory) check(file.mkdir()) { "Création du dossier refusée" }
        else check(file.createNewFile()) { "Création du fichier refusée" }
        run.localKeys[file.path] = localFileKey(file.path) ?: Unit
        run.artifacts.record(OperationArtifacts.Entry(file.path, parent, name, directory, localIdentity(file.path), true, staging,
            run.volume?.uuid, operation = run.artifacts.id, document = File(file.path).canonicalPath))
        Diagnostic.detail(Event.CREATE_RESULT, Result.OK, run.op, run.storage,
            if (directory) Kind.DIRECTORY else Kind.FILE, run.mode, Phase.CREATE)
        return file.path
    }

    private fun supportsRename(path: String): Boolean = flags(path) and D.FLAG_SUPPORTS_RENAME.toLong() != 0L

    private suspend fun copyFile(source: String, parent: String, name: String, run: CopyRun, replace: FileItem?,
        snapshot: Map<String, List<FileItem>>, root: Boolean): String {
        currentCoroutineContext().ensureActive()
        val observer = TransferEvents.observer()
        val expectedSize = sourceSize(source)
        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, name, fileBytes = 0, fileTotal = expectedSize, fileStart = true))
        // 1. Create the temporary target « .<nom>.bonobo-part » (recorded before anything is written).
        val partName = partName(parent, name, run)
        var target = if (isSaf(parent)) createTracked(parent, partName, "application/octet-stream", run.artifacts,
            staging = true, volume = run.volume?.uuid, storage = run.storage, diagnostic = run.op, mode = run.mode)
            else createLocal(parent, partName, directory = false, staging = true, run = run)
        if (root) aliasCheck(snapshot)
        val atomic = !isSaf(target) || supportsRename(target)
        if (!atomic) {
            // Provider without rename: write the final name directly. It stays recorded, so an
            // interruption is cleaned up exactly like a .bonobo-part.
            if (replace != null) throw TransferFailure(FailureKind.OTHER,
                "Ce support ne permet pas de remplacer un fichier de façon sûre. Choisissez Renommer.", usb = run.usb)
            val report = ArtifactCleanup.execute(run.artifacts, cleanupAccess(), { it.path == target },
                run.op, run.storage)
            check(report.overall == ArtifactCleanup.Outcome.DELETED) { "Fichier temporaire non supprimé" }
            target = createTracked(parent, name, (if (isSaf(source)) resolver.getType(uri(source)) else MimeTypeUtils.fromFile(File(source)))
                ?: "application/octet-stream", run.artifacts, staging = true, volume = run.volume?.uuid,
                storage = run.storage, diagnostic = run.op, mode = run.mode)
        }
        // 2. Write, close the streams. Bytes sent and the stored date are recorded in the receipt when the
        // streams close — also on interruption — so cleanup can recognise its own (possibly truncated) partial.
        var sent = 0L
        fun recordClosed(bytes: Long) {
            val (_, modified) = observe(target)
            runCatching { run.artifacts.update(target) { it.copy(bytesSent = bytes, modifiedAt = modified) } }
        }
        val evidence = try { VerifiedStreamCopy.write(
            openInput = { integrityAccess.open(source) },
            openOutput = {
                if (isSaf(target)) {
                    requireFlag(target, D.FLAG_SUPPORTS_WRITE)
                    openSynchronizedOutput(target)
                } else {
                    check(run.artifacts.entries().any { it.path == target }) { "Destination locale non suivie : écriture refusée" }
                    check((localFileKey(target) ?: Unit) == run.localKeys[target]) { "Destination locale remplacée : écriture refusée" }
                    LocalSyncOutput(target)
                }
            }, expected = expectedSize,
            sync = { output ->
                when (output) {
                    is SyncOutput -> output.synchronize()
                    is LocalSyncOutput -> output.synchronize()
                }
            }, progress = { bytes, count ->
                sent = bytes
                observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = count.toLong(), fileBytes = bytes))
            }, operation = run.op)
        } catch (e: Exception) {
            recordClosed(sent)
            throw e
        }
        recordClosed(evidence.bytes)
        // 3. Verify the SHA-256 of what is actually stored (single verification for this file).
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING, fileBytes = 0, fileTotal = evidence.bytes))
        val size = if (isSaf(target)) documentSize(target) else File(target).length()
        VerifiedStreamCopy.verify({ integrityAccess.open(target) }, evidence, size) { read ->
            observer?.invoke(TransferEvents.Event(TransferPhase.VERIFYING, fileBytes = read, fileTotal = evidence.bytes))
        }
        currentCoroutineContext().ensureActive()
        // 4. Only now give it its final name.
        val published = if (atomic) publish(target, parent, name, partName, replace, run) else {
            run.artifacts.entries().filter { it.path == target }.forEach(run.artifacts::forget)
            target
        }
        run.copied++
        runCatching { run.published += integrityAccess.identity(published) }
        if (run.moving) run.hashes[source] = evidence.sha256
        return published
    }

    private fun publish(part: String, parent: String, name: String, partName: String, replace: FileItem?, run: CopyRun): String {
        val receipt = run.artifacts.entries().single { it.path == part }
        if (!isSaf(part)) {
            val from = File(part).toPath()
            val to = File(parent, name).toPath()
            try {
                if (replace == null) {
                    if (SafeFiles.exists(to.toFile())) throw TransferFailure(FailureKind.CONFLICT,
                        "« $name » est apparu dans la destination pendant la copie.", usb = run.usb)
                    // Without REPLACE_EXISTING the move refuses an existing target.
                    java.nio.file.Files.move(from, to)
                } else {
                    // Atomic replacement: the original stays in place until the verified copy takes its name.
                    try { java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    run.replaced++
                }
            } catch (e: TransferFailure) { throw e }
            catch (e: Exception) {
                Diagnostic.detail(Event.RENAME, Result.ERROR, run.op, run.storage, Kind.FILE, run.mode)
                throw TransferFailure(FailureKind.OTHER, "Impossible de donner son nom final à « $name ».",
                    CopyFailures.technical(e), run.usb, e)
            }
            run.artifacts.forget(receipt)
            Diagnostic.detail(Event.RENAME, Result.OK, run.op, run.storage, Kind.FILE, run.mode)
            return to.toString()
        }
        if (replace != null) {
            // The new content is verified: only now remove the original (verified deletion).
            delete(replace.path, Mode.PERMANENT)
            run.replaced++
        }
        var renamed: String? = null
        try {
            val result = mutations.rename(part, parent, name) { updated ->
                renamed = updated
                parents[updated] = parent
                if (updated != part) parents.remove(part)
            }
            run.artifacts.forget(receipt)
            Diagnostic.detail(Event.RENAME, Result.OK, run.op, run.storage, Kind.FILE, run.mode)
            return result
        } catch (e: Exception) {
            Diagnostic.detail(Event.RENAME, Result.ERROR, run.op, run.storage, Kind.FILE, run.mode)
            // The provider may have renamed it under another name (e.g. « nom (1) »): track the real document.
            val actual = renamed?.takeIf { it != part }?.let { moved ->
                runCatching { list(parent).singleOrNull { integrityAccess.identity(it.path) == integrityAccess.identity(moved) } }.getOrNull()
            }
            if (actual != null && actual.name == name) { run.artifacts.forget(receipt); return actual.path }
            if (actual != null) {
                run.artifacts.forget(receipt)
                run.artifacts.record(receipt.copy(path = actual.path, name = actual.name,
                    identity = integrityAccess.identity(actual.path), document = actual.path))
            }
            if (replace != null) {
                // The original was already removed: never delete the only verified copy.
                run.artifacts.entries().filter { it.staging && it.parent == parent && (it.path == part || it.path == actual?.path) }
                    .forEach(run.artifacts::forget)
                throw TransferFailure(FailureKind.OTHER, "Le fichier existant a été remplacé mais le renommage final a échoué : " +
                    "le nouveau contenu vérifié est conservé sous le nom « ${actual?.name ?: partName} ». Renommez-le en « $name ».",
                    CopyFailures.technical(e), run.usb, e)
            }
            throw TransferFailure(FailureKind.OTHER, "Impossible de donner son nom final à « $name ».", CopyFailures.technical(e), run.usb, e)
        }
    }

    /** Move only: one re-read of the source, compared with the SHA-256 computed while copying. */
    private suspend fun verifySourceUnchanged(source: String, result: CopyResult) {
        currentCoroutineContext().ensureActive()
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
        val files = mutableListOf<String>()
        suspend fun walk(path: String) {
            currentCoroutineContext().ensureActive()
            if (isDirectory(path)) children(path).forEach { walk(it.path) } else files += path
        }
        walk(source)
        var matches = files.size == result.hashes.size
        val total = files.sumOf { runCatching { sourceSize(it) }.getOrNull() ?: 0L }
        val observer = TransferEvents.observer()
        var done = 0L
        if (matches) for (file in files) {
            val expected = result.hashes[file]
            val base = done
            if (expected == null || !sha256(file) { read ->
                    observer?.invoke(TransferEvents.Event(TransferPhase.VERIFYING, fileBytes = base + read, fileTotal = total))
                }.contentEquals(expected)) { matches = false; break }
            done += sourceSize(file) ?: 0L
        }
        Diagnostic.event(Event.SHA256, if (matches) Result.MATCH else Result.MISMATCH, result.diagnosticOperation, files.size.toLong())
        check(matches) { "Source modifiée pendant le déplacement : source conservée" }
    }

    private fun documentSize(path: String): Long? = (resolver.query(uri(path), arrayOf(D.COLUMN_SIZE), null, null, null)
        ?: throw IOException("Destination inaccessible pendant le contrôle de taille")).use {
        check(it.moveToFirst()) { "Destination disparue pendant le contrôle de taille" }
        if (it.isNull(0)) null else it.getLong(0).takeIf { size -> size >= 0 }
    }
    private class SyncOutput(private val descriptor: android.os.ParcelFileDescriptor) : java.io.OutputStream() {
        private val delegate = android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        override fun write(value: Int) = delegate.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = delegate.write(buffer, offset, length)
        override fun flush() = delegate.flush()
        fun synchronize() {
            val fd = descriptor.fileDescriptor
            // fsync on pipes/sockets is meaningless. Unexpected sync errors on a regular file fail the copy.
            if (android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd).st_mode)) fd.sync()
        }
        override fun close() {
            var failure: Exception? = null
            try { if (descriptor.canDetectErrors()) descriptor.checkError() } catch (e: Exception) { failure = e }
            try { delegate.close() } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
            failure?.let { throw it }
        }
    }
    private class LocalSyncOutput(path: String) : java.io.OutputStream() {
        private val channel = java.nio.channels.FileChannel.open(File(path).toPath(),
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        private val delegate = java.nio.channels.Channels.newOutputStream(channel)
        override fun write(value: Int) = delegate.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = delegate.write(buffer, offset, length)
        override fun flush() = delegate.flush()
        fun synchronize() = channel.force(true)
        override fun close() = delegate.close()
    }
    private fun openSynchronizedOutput(path: String): java.io.OutputStream {
        val descriptor = resolver.openFileDescriptor(uri(path), "w") ?: throw IOException("Écriture refusée")
        return try { SyncOutput(descriptor) } catch (e: Exception) {
            try { descriptor.close() } catch (close: Exception) { e.addSuppressed(close) }
            throw e
        }
    }
}
