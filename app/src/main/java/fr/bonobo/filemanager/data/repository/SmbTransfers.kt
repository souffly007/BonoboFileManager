package fr.bonobo.filemanager.data.repository

import fr.bonobo.filemanager.diagnostic.Diagnostic
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Event
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Kind
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Mode
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Phase
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Result
import fr.bonobo.filemanager.diagnostic.TechnicalJournal.Storage
import fr.bonobo.filemanager.domain.transfer.ConflictAction
import fr.bonobo.filemanager.domain.transfer.FailureKind
import fr.bonobo.filemanager.domain.transfer.TransferEvents
import fr.bonobo.filemanager.domain.transfer.TransferFailure
import fr.bonobo.filemanager.domain.transfer.TransferPhase
import fr.bonobo.filemanager.domain.transfer.TransferSkipped
import fr.bonobo.filemanager.util.ArtifactCleanup
import fr.bonobo.filemanager.util.IntegrityVerifier
import fr.bonobo.filemanager.util.LocalIntegrityAccess
import fr.bonobo.filemanager.util.OperationArtifacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Verified writes on an SMB share, with the same rules as the USB key:
 *  - every file is written as « .<nom>.<op8>.bonobo-part », its SHA-256 is checked, then it is renamed
 *    on the server; the final name never designates an incomplete file;
 *  - every part and every created folder is recorded BEFORE it is created ([OperationArtifacts], « en
 *    cours »), so an app killed in the middle is cleaned at the next start or connection;
 *  - on cancellation, error or SHA mismatch the part is deleted and its absence verified; created
 *    folders are deleted if empty, kept (KEPT_VERIFIED) when they only hold verified files.
 * Receipts live in their own directory (never mixed with the USB ones). [Entry.volume] =
 * « smb:<id de connexion>:<partage> » tells the cleanup which server and share they belong to; the
 * receipt paths are relative to that share. Journal lines carry storage=SMB and never a path.
 */
class SmbTransfers(private val receipts: File) {

    /** Thrown after the cleanup of a failed write: [leftover] = a partial could not be removed. */
    class Failed(cause: Throwable, val leftover: Boolean) : Exception(cause.message, cause)

    data class Summary(val deleted: Int, val remaining: Int)

    private inner class Run(val volume: String, val mode: Mode) {
        val artifacts = OperationArtifacts(receipts)
        val op: UUID? = Diagnostic.operation()?.let { UUID.fromString(artifacts.id) }
        val tag = artifacts.id.take(8)
        /** Identities of the files published (verified, final name) by this run. */
        val published = mutableSetOf<String>()
    }

    // ─────────────────────────────── Copy / move on the same share ───────────────────────────────

    /**
     * Copies [source] (file or folder) to [target] (final name, absent) on the same share; [move]
     * then deletes the source once the copy and the unchanged source are verified.
     */
    suspend fun copy(disk: SmbDisk, volume: String, source: String, target: String, move: Boolean) {
        val run = Run(volume, if (move) Mode.MOVE else Mode.COPY)
        execute(disk, run) {
            TransferEvents.emit(TransferEvents.Event(TransferPhase.PREPARING))
            copyNode(disk, run, source, target)
            // The copy is complete and verified file by file: created folders are now results.
            run.artifacts.entries().filter { !it.staging }.forEach(run.artifacts::forget)
        }
        if (!move) return
        // Move: the whole target must equal the source as it is NOW (unchanged during the copy).
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
        val access = disk.integrityAccess()
        val copied = IntegrityVerifier.fingerprint(access, target)
        val current = IntegrityVerifier.fingerprint(access, source)
        val same = copied == current
        Diagnostic.detail(Event.SHA256, if (same) Result.MATCH else Result.MISMATCH, run.op, Storage.SMB,
            mode = Mode.MOVE, phase = Phase.VERIFY)
        check(same) { "Source modifiée pendant la copie : la copie est conservée, la source n’est pas supprimée" }
        currentCoroutineContext().ensureActive()
        TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
        val directory = disk.stat(source)?.directory == true
        if (directory) disk.deleteTree(source) else disk.deleteFile(source)
        val gone = disk.stat(source) == null
        Diagnostic.detail(Event.DELETE_VERIFY, if (gone) Result.OK else Result.INDETERMINATE, run.op, Storage.SMB,
            if (directory) Kind.DIRECTORY else Kind.FILE, Mode.MOVE, Phase.VERIFY)
        check(gone) { "Suppression de la source non confirmée : elle est toujours sur le serveur" }
    }

    private suspend fun copyNode(disk: SmbDisk, run: Run, source: String, target: String) {
        currentCoroutineContext().ensureActive()
        val node = disk.stat(source) ?: error("Élément source introuvable sur le serveur")
        require(!node.reparse) { "Point de réanalyse SMB refusé" }
        if (!node.directory) { copyFile(disk, run, source, node, target); return }
        createRecordedFolder(disk, run, target)
        for (child in disk.list(source)) {
            copyNode(disk, run, SmbPaths.join(source, child.name), SmbPaths.join(target, child.name))
        }
    }

    private suspend fun copyFile(disk: SmbDisk, run: Run, source: String, node: SmbNode, target: String) {
        val context = currentCoroutineContext()
        val observer = TransferEvents.observer()
        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, node.name, fileBytes = 0, fileTotal = node.size, fileStart = true))
        val streamed = writePart(disk, run, target) { output ->
            val digest = sha256()
            var total = 0L
            var previous = 0L
            disk.openRead(source).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    context.ensureActive()
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    total += count
                    check(total <= node.size) { "La taille du fichier source a changé pendant la copie" }
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = total - previous, fileBytes = total))
                    previous = total
                }
            }
            check(total == node.size) { "Lecture incomplète : $total octets lus sur ${node.size}" }
            total to hex(digest.digest())
        }
        publish(disk, run, target, streamed, replace = false)
    }

    // ─────────────────────────────── Phone → server (upload) ───────────────────────────────

    /** Element of the phone (internal storage or SAF / USB key) read for an upload. */
    interface UploadSource : IntegrityVerifier.Access {
        fun size(path: String): Long
    }

    /** [target]: published path on the share; [hashes]: SHA-256 per source file (copied or found identical). */
    data class UploadResult(val target: String, val hashes: Map<String, String>)

    /**
     * Sends [sourcePath] (file or folder named [name]) into [targetDirectory] of the share, with the same
     * conflict rules as a copy to the USB key:
     *  - ASK: refused (CONFLICT) so the user chooses; SKIP: left untouched (TransferSkipped);
     *  - RENAME: « nom (1) »; REPLACE: verified part renamed over the file, folders merged with differing
     *    files replaced; MERGE: missing files added, identical ones (same size, + SHA-256 when [compareHash]
     *    or [moving]) skipped, differing ones copied as « nom (1) ».
     */
    suspend fun upload(disk: SmbDisk, volume: String, source: UploadSource, sourcePath: String, name: String,
        targetDirectory: String, conflict: ConflictAction, compareHash: Boolean, moving: Boolean): UploadResult {
        SmbPaths.validateName(name)
        val run = Run(volume, if (moving) Mode.MOVE else Mode.COPY)
        val hashes = linkedMapOf<String, String>()
        val hashing = compareHash || moving
        var published = ""
        execute(disk, run) {
            TransferEvents.emit(TransferEvents.Event(TransferPhase.PREPARING))
            val directory = source.children(sourcePath) != null
            val requested = SmbPaths.join(targetDirectory, name)
            val existing = disk.stat(requested)
            var target = requested
            var mergeInto = false
            var replace = false
            if (existing != null) {
                val kind = if (directory) Kind.DIRECTORY else Kind.FILE
                Diagnostic.detail(Event.DESTINATION_CONFLICT, Result.CONFLICT, run.op, Storage.SMB, kind, run.mode)
                when (conflict) {
                    ConflictAction.ASK -> throw TransferFailure(FailureKind.CONFLICT,
                        "« $name » existe déjà dans le dossier du serveur. Choisissez Remplacer, Renommer, Ignorer" +
                            (if (directory) " ou Fusionner." else "."))
                    ConflictAction.SKIP -> {
                        Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.SKIPPED, run.op, Storage.SMB, kind, run.mode)
                        throw TransferSkipped("Ignoré : « $name » existe déjà sur le serveur.")
                    }
                    ConflictAction.RENAME -> {
                        target = SmbPaths.uniqueCandidate(requested) { disk.stat(it) != null }
                        Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.RENAMED, run.op, Storage.SMB, kind, run.mode)
                    }
                    ConflictAction.MERGE, ConflictAction.REPLACE -> {
                        if (existing.directory != directory) throw TransferFailure(FailureKind.CONFLICT,
                            "« $name » existe déjà sur le serveur mais n’est pas du même type (fichier / dossier). Choisissez Renommer ou Ignorer.")
                        when {
                            directory -> { mergeInto = true; target = SmbPaths.join(targetDirectory, existing.name)
                                Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.MERGED, run.op, Storage.SMB, kind, run.mode) }
                            conflict == ConflictAction.REPLACE -> { replace = true
                                Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.REPLACED, run.op, Storage.SMB, kind, run.mode) }
                            identical(disk, source, sourcePath, requested, existing, hashing, hashes) -> {
                                Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.SKIPPED, run.op, Storage.SMB, kind, run.mode)
                                throw TransferSkipped("Identique au fichier déjà présent : ignoré.")
                            }
                            else -> { target = SmbPaths.uniqueCandidate(requested) { disk.stat(it) != null }
                                Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.RENAMED, run.op, Storage.SMB, kind, run.mode) }
                        }
                    }
                }
            }
            published = target
            if (directory) uploadDirectory(disk, run, source, sourcePath, target, mergeInto, conflict, hashing, hashes)
            else uploadFile(disk, run, source, sourcePath, target, replace, hashes)
            // Upload complete, every file verified: created folders are results now.
            run.artifacts.entries().filter { !it.staging }.forEach(run.artifacts::forget)
        }
        return UploadResult(published, hashes.toMap())
    }

    private suspend fun uploadDirectory(disk: SmbDisk, run: Run, source: UploadSource, sourcePath: String, target: String,
        mergeInto: Boolean, conflict: ConflictAction, hashing: Boolean, hashes: MutableMap<String, String>) {
        currentCoroutineContext().ensureActive()
        if (!mergeInto) createRecordedFolder(disk, run, target)
        val present = if (mergeInto) disk.list(target).associateBy { it.name.lowercase(java.util.Locale.ROOT) } else emptyMap()
        val children = source.children(sourcePath) ?: error("Dossier source devenu un fichier")
        require(children.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == children.size) {
            "Deux éléments ne diffèrent que par la casse : le serveur ne peut pas les distinguer"
        }
        for (child in children.sortedBy { it.name }) {
            currentCoroutineContext().ensureActive()
            SmbPaths.validateName(child.name)
            val childDirectory = source.children(child.path) != null
            val existing = present[child.name.lowercase(java.util.Locale.ROOT)]
            val childTarget = SmbPaths.join(target, existing?.name ?: child.name)
            when {
                existing == null -> uploadNode(disk, run, source, child.path, childTarget, childDirectory, conflict, hashing, hashes)
                existing.directory && childDirectory ->
                    uploadDirectory(disk, run, source, child.path, childTarget, true, conflict, hashing, hashes)
                existing.directory != childDirectory -> {
                    Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.RENAMED, run.op, Storage.SMB, mode = run.mode)
                    uploadNode(disk, run, source, child.path, SmbPaths.uniqueCandidate(childTarget) { disk.stat(it) != null },
                        childDirectory, conflict, hashing, hashes)
                }
                identical(disk, source, child.path, childTarget, existing, hashing, hashes) -> {
                    val observer = TransferEvents.observer()
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, child.path, child.name, fileBytes = existing.size,
                        fileTotal = existing.size, fileStart = true))
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = existing.size, fileBytes = existing.size))
                }
                conflict == ConflictAction.REPLACE -> {
                    Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.REPLACED, run.op, Storage.SMB, Kind.FILE, run.mode)
                    uploadFile(disk, run, source, child.path, childTarget, true, hashes)
                }
                else -> {
                    Diagnostic.detail(Event.CONFLICT_RESOLVED, Result.RENAMED, run.op, Storage.SMB, Kind.FILE, run.mode)
                    uploadFile(disk, run, source, child.path, SmbPaths.uniqueCandidate(childTarget) { disk.stat(it) != null }, false, hashes)
                }
            }
        }
    }

    private suspend fun uploadNode(disk: SmbDisk, run: Run, source: UploadSource, path: String, target: String, directory: Boolean,
        conflict: ConflictAction, hashing: Boolean, hashes: MutableMap<String, String>) =
        if (directory) uploadDirectory(disk, run, source, path, target, false, conflict, hashing, hashes)
        else uploadFile(disk, run, source, path, target, false, hashes)

    private suspend fun uploadFile(disk: SmbDisk, run: Run, source: UploadSource, path: String, target: String,
        replace: Boolean, hashes: MutableMap<String, String>) {
        val context = currentCoroutineContext()
        val observer = TransferEvents.observer()
        val size = source.size(path)
        val name = nameOf(target)
        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, path, name, fileBytes = 0, fileTotal = size, fileStart = true))
        val written = writePart(disk, run, target) { output ->
            val digest = sha256()
            var total = 0L
            var previous = 0L
            source.open(path).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    context.ensureActive()
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    total += count
                    check(total <= size) { "La taille du fichier source a changé pendant l’envoi" }
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = total - previous, fileBytes = total))
                    previous = total
                }
            }
            check(total == size) { "Lecture incomplète : $total octets lus sur $size" }
            total to hex(digest.digest())
        }
        publish(disk, run, target, written, replace)
        hashes[path] = written.second
    }

    /** Same size; with [hashing] the SHA-256 of the source and of the server file must match too. */
    private suspend fun identical(disk: SmbDisk, source: UploadSource, path: String, remote: String, existing: SmbNode,
        hashing: Boolean, hashes: MutableMap<String, String>): Boolean {
        if (existing.directory || existing.size != source.size(path)) return false
        if (!hashing) return true
        val local = IntegrityVerifier.fingerprint(source, path)
        val match = local == IntegrityVerifier.fingerprint(disk.integrityAccess(), remote)
        Diagnostic.detail(Event.SHA256, if (match) Result.MATCH else Result.MISMATCH, null, Storage.SMB, Kind.FILE, phase = Phase.VERIFY)
        if (match) hashes[path] = local
        return match
    }

    /**
     * Move to the server: the source may be deleted only if it still holds exactly the files sent (or
     * found identical) with the same SHA-256 — no file added, removed or modified since.
     */
    suspend fun verifyUnchanged(source: UploadSource, root: String, hashes: Map<String, String>) {
        val seen = mutableSetOf<String>()
        suspend fun walk(path: String) {
            currentCoroutineContext().ensureActive()
            val children = source.children(path)
            if (children == null) {
                val expected = hashes[path] ?: error("Fichier ajouté pendant l’envoi : source conservée")
                check(IntegrityVerifier.fingerprint(source, path) == expected) { "Source modifiée pendant l’envoi : source conservée" }
                seen += path
            } else children.forEach { walk(it.path) }
        }
        walk(root)
        check(seen == hashes.keys) { "Source modifiée pendant l’envoi : source conservée" }
    }

    // ─────────────────────────────── Explorer: new folder, rename ───────────────────────────────

    /** Creates [name] in [parent]; invalid or existing name refused with a short French message. */
    fun createFolder(disk: SmbDisk, parent: String, name: String): String {
        SmbPaths.validateName(name)
        val target = SmbPaths.join(parent, name)
        disk.stat(target)?.let { existing ->
            throw IllegalStateException("« ${existing.name} » existe déjà dans ce dossier.")
        }
        disk.mkdir(target)
        check(disk.stat(target)?.directory == true) { "Création du dossier non confirmée par le serveur." }
        return target
    }

    /**
     * Renames [path] to [newName] in the same folder. Refused when another element already carries that
     * name (case-insensitive); a change of case only is allowed. Returns the new path.
     */
    fun renameEntry(disk: SmbDisk, path: String, newName: String): String {
        SmbPaths.validateName(newName)
        require(path.isNotBlank()) { "La racine du partage ne peut pas être renommée." }
        val current = disk.stat(path) ?: throw IllegalStateException("Élément introuvable sur le serveur.")
        if (current.name == newName) return path
        val target = SmbPaths.join(parentOf(path), newName)
        val sameElement = SmbPaths.identity(target) == SmbPaths.identity(path)
        if (!sameElement) disk.stat(target)?.let { existing ->
            throw IllegalStateException("« ${existing.name} » existe déjà dans ce dossier : renommage refusé.")
        }
        disk.rename(path, target, replace = false)
        check(disk.stat(target) != null && (sameElement || disk.stat(path) == null)) { "Renommage non confirmé par le serveur." }
        return target
    }

    // ─────────────────────────────── Server tree snapshot (server → phone) ───────────────────────

    /** Metadata of a server element and its descendants, keyed by path relative to [root] ("" = root). */
    data class Meta(val directory: Boolean, val size: Long, val changed: Long)

    fun snapshot(disk: SmbDisk, root: String): Map<String, Meta> {
        val result = linkedMapOf<String, Meta>()
        fun walk(path: String, relative: String, depth: Int) {
            require(depth <= 64) { "Arborescence trop profonde : transfert refusé" }
            val node = disk.stat(path) ?: error("Élément source introuvable sur le serveur")
            require(!node.reparse) { "Point de réanalyse SMB refusé" }
            result[relative] = Meta(node.directory, if (node.directory) 0 else node.size, if (node.directory) 0 else node.changed)
            if (node.directory) disk.list(path).forEach { child ->
                walk(SmbPaths.join(path, child.name), if (relative.isEmpty()) child.name else "$relative\\${child.name}", depth + 1)
            }
        }
        walk(root, "", 0)
        return result
    }

    // ─────────────────────────────── Replace a file (text editor) ───────────────────────────────

    /**
     * Replaces [path] with the content of [local]: the part is written and verified, then renamed over
     * the original by the server. The original is never opened for writing: an interruption leaves it intact.
     */
    suspend fun replace(disk: SmbDisk, volume: String, path: String, local: File) {
        val run = Run(volume, Mode.COPY)
        execute(disk, run) {
            val expected = IntegrityVerifier.fingerprint(LocalIntegrityAccess, local.path)
            val written = writePart(disk, run, path) { output ->
                var total = 0L
                local.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
                total to expected
            }
            publish(disk, run, path, written, replace = true)
        }
    }

    // ─────────────────────────────── Shared steps ───────────────────────────────

    /** Folder recorded BEFORE its creation; confirmed (owned) only once the server created it. */
    private fun createRecordedFolder(disk: SmbDisk, run: Run, target: String) {
        val entry = OperationArtifacts.Entry(path = target, parent = parentOf(target), name = nameOf(target), directory = true,
            identity = SmbPaths.identity(target), owned = false, staging = false, volume = run.volume,
            operation = run.artifacts.id)
        run.artifacts.record(entry)
        try { disk.mkdir(target) } catch (e: Exception) { run.artifacts.forget(entry); throw e }
        run.artifacts.confirm(target)
    }

    /** Part name next to [target]: « .<nom>.<op8>.bonobo-part ». */
    fun partOf(target: String, tag: String): String =
        SmbPaths.join(parentOf(target), ".${nameOf(target)}.$tag${OperationArtifacts.PART_SUFFIX}")

    /** Records then creates the part, writes it with [write] (returns bytes + expected SHA), closes it. */
    private suspend fun writePart(disk: SmbDisk, run: Run, target: String,
        write: suspend (java.io.OutputStream) -> Pair<Long, String>): Pair<String, String> {
        val part = partOf(target, run.tag)
        val entry = OperationArtifacts.Entry(path = part, parent = parentOf(part), name = nameOf(part), directory = false,
            identity = SmbPaths.identity(part), owned = false, staging = true, volume = run.volume, operation = run.artifacts.id)
        run.artifacts.record(entry)
        var sent = 0L
        var expected = ""
        try {
            disk.createNew(part).use { raw ->
                // Upper bound of what reached the server, counted BEFORE each write so that a write failing
                // half-way still covers the bytes it may have sent (cleanup accepts any size ≤ bytesSent).
                val output = object : java.io.FilterOutputStream(raw) {
                    override fun write(b: Int) { sent++; out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { sent += len; out.write(b, off, len) }
                }
                val (bytes, sha) = write(output)
                output.flush()
                check(bytes == sent) { "Écriture incomplète : $sent octets envoyés sur $bytes" }
                expected = sha
            }
        } finally {
            // Bytes sent at close (success or interruption): cleanup accepts any size ≤ this value.
            runCatching { run.artifacts.update(part) { it.copy(bytesSent = sent) } }
        }
        return part to expected
    }

    /** Verifies the part's SHA-256, then renames it to [target] on the server. */
    private suspend fun publish(disk: SmbDisk, run: Run, target: String, written: Pair<String, String>, replace: Boolean) {
        val (part, expected) = written
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
        val actual = IntegrityVerifier.fingerprint(disk.integrityAccess(), part)
        val match = actual == expected
        Diagnostic.detail(Event.SHA256, if (match) Result.MATCH else Result.MISMATCH, run.op, Storage.SMB,
            Kind.FILE, run.mode, Phase.VERIFY)
        check(match) { "Intégrité non confirmée (SHA-256 différent) : copie partielle supprimée" }
        currentCoroutineContext().ensureActive()
        disk.rename(part, target, replace)
        Diagnostic.detail(Event.RENAME, Result.OK, run.op, Storage.SMB, Kind.FILE, run.mode, Phase.FINISH)
        run.artifacts.entries().firstOrNull { it.path == part }?.let(run.artifacts::forget)
        run.published += SmbPaths.identity(target)
    }

    /** Runs [block] as one recorded operation; on any failure cleans what it created, then rethrows. */
    private suspend fun execute(disk: SmbDisk, run: Run, block: suspend () -> Unit) {
        OperationArtifacts.markRunning(run.artifacts.id)
        run.artifacts.begin()
        Diagnostic.detail(Event.COPY_START, Result.START, run.op, Storage.SMB, mode = run.mode, phase = Phase.REQUEST)
        try {
            block()
            Diagnostic.detail(Event.COPY_END, Result.OK, run.op, Storage.SMB, mode = run.mode, phase = Phase.FINISH)
        } catch (e: Throwable) {
            val leftover = withContext(NonCancellable) { cleanupAfterFailure(disk, run) }
            Diagnostic.detail(Event.COPY_END, when (e) {
                    is CancellationException -> Result.CANCELLED
                    is TransferSkipped -> Result.SKIPPED
                    else -> Result.ERROR
                },
                run.op, Storage.SMB, mode = run.mode, phase = Phase.FINISH)
            if (e is CancellationException) throw e
            // A conflict refusal or a skip is the expected answer, not a failure to wrap.
            if (e is TransferSkipped || (e is TransferFailure && !leftover)) throw e
            throw Failed(e, leftover)
        } finally {
            runCatching { run.artifacts.end() }
            OperationArtifacts.markFinished(run.artifacts.id)
        }
    }

    /** @return true when something recorded could not be removed (kept as deferred for the next connection). */
    private fun cleanupAfterFailure(disk: SmbDisk, run: Run): Boolean {
        if (run.artifacts.entries().isEmpty()) return false
        val report = runCatching {
            ArtifactCleanup.execute(run.artifacts, cleanupAccess(disk), diagnosticOperation = run.op, storage = Storage.SMB,
                verified = { node -> !node.name.endsWith(OperationArtifacts.PART_SUFFIX) && SmbPaths.identity(node.path) in run.published })
        }.getOrNull()
        val remaining = report?.remaining ?: run.artifacts.entries()
        // Server unreachable or refusal: retried at the next start / connection to this share.
        remaining.forEach { entry -> runCatching { run.artifacts.record(entry.copy(deferred = true)) } }
        return remaining.isNotEmpty()
    }

    // ─────────────────────────────── Cleanup (failure, next start, next connection) ───────────────

    fun cleanupAccess(disk: SmbDisk): ArtifactCleanup.Access = object : ArtifactCleanup.Access {
        override fun list(parent: String): List<ArtifactCleanup.Node> {
            val folder = disk.stat(parent) ?: throw java.io.FileNotFoundException("Dossier SMB absent")
            check(folder.directory) { "Parent SMB non dossier" }
            return disk.list(parent).map { node ->
                val path = SmbPaths.join(parent, node.name)
                ArtifactCleanup.Node(path, node.name, node.directory, SmbPaths.identity(path), if (node.directory) null else node.size)
            }
        }
        override fun delete(entry: OperationArtifacts.Entry) {
            if (entry.directory) disk.deleteEmptyDirectory(entry.path) else disk.deleteFile(entry.path)
        }
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = true
    }

    /**
     * Receipts of an app killed during an SMB write become deferred (same rule as the USB key); the
     * PENDING_CLEANUP line carries storage=SMB and the op of the interrupted write.
     */
    fun adoptInterrupted(): Int {
        var adopted = 0
        for (id in runCatching { OperationArtifacts.interrupted(receipts) }.getOrDefault(emptyList())) {
            val operation = runCatching { OperationArtifacts.open(receipts, id) }.getOrNull() ?: continue
            val entries = operation.entries()
            entries.filter { !it.deferred }.forEach { operation.record(it.copy(deferred = true)) }
            operation.end()
            if (entries.isEmpty()) continue
            adopted += entries.size
            Diagnostic.detail(Event.PENDING_CLEANUP, Result.START, original(id), Storage.SMB,
                mode = Mode.CLEANUP, expected = entries.size.toLong())
        }
        return adopted
    }

    /** Volumes (« smb:<id>:<partage> ») that still hold deferred receipts. */
    fun pendingVolumes(): Set<String> = runCatching {
        OperationArtifacts.pending(receipts).filter { it.deferred }.mapNotNull { it.volume }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Cleans the deferred receipts of [volume] with [disk] (the share is reachable). What cannot be
     * removed stays deferred for the next attempt.
     */
    fun cleanDeferred(volume: String, disk: SmbDisk): Summary {
        adoptInterrupted()
        var deleted = 0; var remaining = 0
        for (id in runCatching { OperationArtifacts.finishedIds(receipts) }.getOrDefault(emptyList())) {
            val operation = runCatching { OperationArtifacts.open(receipts, id) }.getOrNull() ?: continue
            if (operation.isLive) continue
            val ready = operation.entries().filter { it.deferred && it.volume == volume }
            if (ready.isEmpty()) continue
            val diagnostic = original(id)
            val report = runCatching {
                ArtifactCleanup.execute(operation, cleanupAccess(disk), { it in ready }, diagnostic, Storage.SMB,
                    verified = { node -> !node.name.endsWith(OperationArtifacts.PART_SUFFIX) })
            }.getOrNull()
            val left = report?.remaining?.size ?: ready.size
            deleted += ready.size - left
            remaining += left
            Diagnostic.detail(Event.PENDING_CLEANUP_DONE, when {
                report == null -> Result.UNKNOWN
                left == 0 && report.count(ArtifactCleanup.Outcome.KEPT) > 0 -> Result.KEPT_VERIFIED
                left == 0 -> Result.DELETED
                report.overall == ArtifactCleanup.Outcome.UNKNOWN -> Result.UNKNOWN
                else -> Result.LEFTOVER
            }, diagnostic, Storage.SMB, mode = Mode.CLEANUP, expected = ready.size.toLong(),
                transferred = (ready.size - left).toLong())
        }
        return Summary(deleted, remaining)
    }

    companion object {
        fun volume(connectionId: Long, share: String) = "smb:$connectionId:${share.lowercase(java.util.Locale.ROOT)}"
        /** Connection id and share of a receipt volume, or null. */
        fun parseVolume(volume: String): Pair<Long, String>? {
            if (!volume.startsWith("smb:")) return null
            val rest = volume.removePrefix("smb:")
            val id = rest.substringBefore(':').toLongOrNull() ?: return null
            val share = rest.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
            return id to share
        }
        fun parentOf(path: String) = path.replace('/', '\\').trimEnd('\\').substringBeforeLast('\\', "")
        fun nameOf(path: String) = path.replace('/', '\\').trimEnd('\\').substringAfterLast('\\')
        /** SHA-256 with the 0x00 prefix used by [IntegrityVerifier] for a file. */
        fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256").apply { update(0.toByte()) }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun original(id: String): UUID? =
            Diagnostic.operation()?.let { runCatching { UUID.fromString(id) }.getOrNull() ?: it }
    }
}
