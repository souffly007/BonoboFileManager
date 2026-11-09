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
        operation: OperationArtifacts? = null, staging: Boolean = false): String {
        validateName(name)
        val intent = OperationArtifacts.Entry(destination(parent, name), parent, name, mime == D.MIME_TYPE_DIR,
            "unconfirmed:${java.util.UUID.randomUUID()}", false, staging)
        val created = mutations.create(parent, name, mime == D.MIME_TYPE_DIR, mime,
            beforeCreate = { operation?.record(intent) }) { node ->
            parents[node.path] = parent
            operation?.record(OperationArtifacts.Entry(node.path, parent, name, node.directory,
                integrityAccess.identity(node.path), false, staging))
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
        val trace = DeletionTrace(Storage.SAF, kind, mode)
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
    suspend fun move(source: String, target: String) {
        if (isSaf(source)) requireDelete(source)
        val created = copy(source, target)
        // The copy is verified (SHA-256 tree check) before the source is deleted:
        // retaining the source on USB/SAF destinations made every move to a USB key fail.
        VerifiedMove.finish(created, retainSource = false,
            verify = { verifyTree(source, created) }, delete = {
                TransferEvents.emit(TransferEvents.Event(TransferPhase.DELETING))
                currentCoroutineContext().ensureActive()
                if (isSaf(source)) {
                    resolveParent(source)
                    delete(source)
                } else {
                    val file = File(source)
                    val trace = DeletionTrace(Storage.LOCAL,
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

    suspend fun copy(source: String, target: String): String {
        val operation = OperationArtifacts(artifactDirectory(context))
        var published: String? = null
        try {
            published = copyDirect(source, target, operation, staging = false, measured = true)
            currentCoroutineContext().ensureActive()
            // A verified published copy is retained even if disposal of the staging area fails.
            operation.entries().filter { !it.staging }.forEach(operation::forget)
            val residues = cleanup(operation)
            check(residues.isEmpty()) { "Copie vérifiée immédiatement ; nettoyage incomplet. $residues" }
            return published
        } catch (e: Exception) {
            Diagnostic.event(if (e is kotlinx.coroutines.CancellationException) Event.CANCEL else Event.SAF_FAILURE,
                if (e is kotlinx.coroutines.CancellationException) Result.CANCELLED else Result.ERROR)
            val residues = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cleanup(operation) }
            val detail = if (residues.isEmpty()) "Aucun résidu suivi ; nettoyage confirmé."
                else "${if (residues.any { it.directory }) "Dossier partiel conservé. " else ""}Résidu(s) à examiner manuellement (aucun écrasement) : " +
                    residues.joinToString(" ; ") { it.path }
            if (e is kotlinx.coroutines.CancellationException) throw kotlinx.coroutines.CancellationException(
                "Annulé ${if (residues.isEmpty()) "sans résidu suivi" else "avec résidu"}. $detail Source conservée."
            ).also { it.initCause(e) }
            throw failure(IOException("${e.message.orEmpty()} Source conservée. $detail", e))
        }
    }
    private fun localIdentity(path: String): String {
        val attrs = java.nio.file.Files.readAttributes(File(path).toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(!attrs.isSymbolicLink && attrs.fileKey() != null) { "Identité locale indéterminée" }
        return "${attrs.fileKey()}:${attrs.creationTime()}"
    }
    private fun cleanup(operation: OperationArtifacts, stagingOnly: Boolean = false): List<OperationArtifacts.Entry> = ArtifactCleanup.run(operation, object : ArtifactCleanup.Access {
        override fun supportsEmptyDirectoryDelete(entry: OperationArtifacts.Entry) = !isSaf(entry.path)
        override fun list(parent: String): List<ArtifactCleanup.Node> = if (isSaf(parent))
            this@SafFiles.list(parent).map { ArtifactCleanup.Node(it.path, it.name, it.isDirectory, integrityAccess.identity(it.path)) }
        else (File(parent).listFiles() ?: throw IOException("Dossier de nettoyage inaccessible")).map {
            ArtifactCleanup.Node(it.path, it.name, it.isDirectory, localIdentity(it.path))
        }
        override fun delete(entry: OperationArtifacts.Entry) {
            if (isSaf(entry.path)) {
                parents[entry.path] = entry.parent
                this@SafFiles.delete(entry.path, Mode.CLEANUP)
            } else java.nio.file.Files.delete(File(entry.path).toPath())
        }
    }, select = { !stagingOnly || it.staging })

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
    private suspend fun copyDirect(source: String, target: String, operation: OperationArtifacts,
        staging: Boolean, measured: Boolean): String {
        // Snapshot before creating anything: opaque providers may alias local storage.
        // Never discover freshly created destination children as new source entries.
        val snapshot = mutableMapOf<String, List<FileItem>>()
        val visited = mutableSetOf<String>()
        suspend fun collect(path: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= fr.bonobo.filemanager.domain.transfer.TransferInventory.MAX_DEPTH) { "Arborescence trop profonde" }
            val directory = if (isSaf(path)) {
                val mime = resolver.getType(uri(path)) ?: error("Source SAF inaccessible")
                mime == D.MIME_TYPE_DIR
            } else {
                require(!java.nio.file.Files.isSymbolicLink(File(path).toPath())) { "Copie de lien symbolique non autorisée" }
                File(path).isDirectory
            }
            if (!directory) return
            require(visited.add(integrityAccess.identity(path))) { "Cycle dans la source" }
            val children = if (isSaf(path)) list(path) else
                (File(path).listFiles() ?: throw IOException("Dossier source inaccessible")).map { with(FileUtils) { it.toFileItem() } }
            snapshot[path] = children
            children.forEach { collect(it.path, depth + 1) }
        }
        collect(source, 0)
        val parent = if (isSaf(target)) uri(target).buildUpon().clearQuery().build().toString() else File(target).parent ?: error("Destination invalide")
        require(integrityAccess.identity(parent) !in visited) { "Destination dans la source : transfert refusé" }
        return copySnapshot(source, target, snapshot, operation, staging, measured)
    }
    private suspend fun copySnapshot(source: String, target: String, snapshot: Map<String, List<FileItem>>, operation: OperationArtifacts, staging: Boolean, measured: Boolean, rootCopy: Boolean = true): String {
        currentCoroutineContext().ensureActive()
        if (!isSaf(source)) require(File(source).exists()) { "Source inaccessible" }
        val targetUri = uri(target)
        val name = if (isSaf(target)) targetUri.getQueryParameter("bonobo_name") ?: error("Destination invalide") else File(target).name
        validateName(name)
        val parent = if (isSaf(target)) targetUri.buildUpon().clearQuery().build().toString() else File(target).parent ?: error("Destination invalide")
        val directory = if (isSaf(source)) resolver.getType(uri(source)) == D.MIME_TYPE_DIR else File(source).isDirectory
        if (!isSaf(source)) require(!java.nio.file.Files.isSymbolicLink(File(source).toPath())) { "Copie de lien symbolique non autorisée" }
        if (directory && !isSaf(source) && !isSaf(parent)) {
            require(!File(target).canonicalFile.toPath().startsWith(File(source).canonicalFile.toPath())) { "Copie d'un dossier dans lui-même interdite" }
        }
        if (directory && isSaf(source) && isSaf(parent) && uri(source).authority == uri(parent).authority) {
            require(DC.getDocumentId(uri(source)) != DC.getDocumentId(uri(parent))) { "Copie d'un dossier dans lui-même interdite" }
            val descendant = try {
                if (android.os.Build.VERSION.SDK_INT >= 29) DC.isChildDocument(resolver, uri(source), uri(parent))
                else containsDirectory(source, parent)
            }
                catch (_: UnsupportedOperationException) { containsDirectory(source, parent) }
            require(!descendant) { "Copie d'un dossier dans un descendant interdite" }
        }
        if (!directory && isSaf(parent) && !staging && measured) {
            check(list(parent).none { it.name == name }) { "Un fichier portant ce nom existe déjà" }
            val temporary = destination(parent, ".bonobo-${java.util.UUID.randomUUID()}-${name.takeLast(100)}")
            val result = ControlledPublication.run(
                stage = { copyDirect(source, temporary, operation, staging = true, measured = true) },
                publish = { staged -> copyDirect(staged, target, operation, staging = false, measured = false) },
                verify = { verifyTree(source, it) })
            val residues = cleanup(operation, stagingOnly = true)
            check(residues.isEmpty()) { "Publication vérifiée immédiatement ; temporaire non supprimé : $residues" }
            return result
        }
        val observer = if (measured) TransferEvents.observer() else null
        observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, name))
        val created = if (isSaf(parent)) {
            val mime = if (directory) D.MIME_TYPE_DIR
                else (if (isSaf(source)) resolver.getType(uri(source)) else MimeTypeUtils.fromFile(File(source)))
                    ?: "application/octet-stream"
            createTracked(parent, name, mime, operation, staging)
        } else {
            val file = File(parent, name)
            if (SafeFiles.exists(file)) {
                Diagnostic.detail(Event.DESTINATION_CONFLICT, Result.CONFLICT, storage = Storage.LOCAL,
                    kind = if (directory) Kind.DIRECTORY else Kind.FILE, phase = Phase.CREATE)
                error("Destination déjà occupée. Choisissez un autre nom ou examinez le dossier partiel avant une nouvelle copie ; aucun remplacement automatique.")
            }
            val intent = OperationArtifacts.Entry(file.path, parent, name, directory,
                "unconfirmed:${java.util.UUID.randomUUID()}", false, staging)
            operation.record(intent)
            if (directory) check(file.mkdir()) else check(file.createNewFile())
            operation.record(OperationArtifacts.Entry(file.path, parent, name, directory, localIdentity(file.path), true, staging))
            Diagnostic.detail(Event.CREATE_RESULT, Result.OK, storage = Storage.LOCAL,
                kind = if (directory) Kind.DIRECTORY else Kind.FILE, phase = Phase.CREATE)
            file.path
        }
        if (rootCopy) {
            // An unresolvable cross-provider alias must not make our creation
            // a new member of the source tree. Stop before writing any content.
            for ((path, originalChildren) in snapshot) {
                currentCoroutineContext().ensureActive()
                val now = integrityAccess.children(path) ?: error("Type source modifié")
                val unchanged = now.map { it.name to it.path }.toSet() == originalChildren.map { it.name to it.path }.toSet()
                check(unchanged) { "Source modifiée ou destination alias de la source : copie refusée" }
            }
        }
        if (directory) {
            val children = snapshot[source] ?: error("Inventaire source absent")
            children.forEach { copySnapshot(it.path, destination(created, it.name), snapshot, operation, staging, measured, rootCopy = false) }
            val actualNames = if (isSaf(created)) list(created).map { it.name }.toSet()
                else File(created).list()?.toSet() ?: throw IOException("Destination inaccessible")
            check(actualNames == children.map { it.name }.toSet()) { "Dossier copié non confirmé" }
        } else {
            val expectedSize = if (isSaf(source)) resolver.query(uri(source), arrayOf(D.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L else File(source).length()
            observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, source, name, fileBytes = 0, fileTotal = expectedSize.takeIf { it >= 0 }, fileStart = true))
            val evidence = VerifiedStreamCopy.write(
                openInput = { integrityAccess.open(source) },
                openOutput = {
                    if (isSaf(created)) {
                        requireFlag(created, D.FLAG_SUPPORTS_WRITE)
                        openSynchronizedOutput(created)
                    } else {
                        val receipt = operation.entries().single { it.path == created }
                        check(localIdentity(created) == receipt.identity) { "Destination locale remplacée : écriture refusée" }
                        LocalSyncOutput(created)
                    }
                }, expected = expectedSize.takeIf { it >= 0 },
                sync = { output ->
                    when (output) {
                        is SyncOutput -> output.synchronize()
                        is LocalSyncOutput -> output.synchronize()
                    }
                }, progress = { bytes, count ->
                    observer?.invoke(TransferEvents.Event(TransferPhase.COPYING, bytesDelta = count.toLong(), fileBytes = bytes))
                })
            if (measured) TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
            val size = if (isSaf(created)) documentSize(created) else File(created).length()
            VerifiedStreamCopy.verify({ integrityAccess.open(created) }, evidence, size)
        }
        if (isSaf(parent)) {
            check(list(parent).any { it.path == created && it.name == name && it.isDirectory == directory }) { "Document absent de la destination après écriture" }
        } else check(File(created).exists()) { "Destination disparue après écriture" }
        if (directory) verifyTree(source, created)
        return created
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

    private suspend fun verifyTree(source: String, target: String) {
        currentCoroutineContext().ensureActive()
        TransferEvents.emit(TransferEvents.Event(TransferPhase.VERIFYING))
        val sourceDirectory = if (isSaf(source)) resolver.getType(uri(source)) == D.MIME_TYPE_DIR else File(source).isDirectory
        val targetDirectory = if (isSaf(target)) resolver.getType(uri(target)) == D.MIME_TYPE_DIR else File(target).isDirectory
        check(sourceDirectory == targetDirectory) { "Type de destination non confirmé" }
        if (sourceDirectory) {
            fun children(path: String) = if (isSaf(path)) list(path) else
                (File(path).listFiles() ?: throw IOException("Dossier inaccessible")).map { with(FileUtils) { it.toFileItem() } }
            val originals = children(source)
            val copies = children(target)
            check(originals.size == copies.size && originals.map { it.name }.toSet() == copies.map { it.name }.toSet() && copies.map { it.name }.distinct().size == copies.size) { "Arborescence non confirmée : source conservée" }
            originals.forEach { original -> verifyTree(original.path, copies.single { it.name == original.name }.path) }
        } else {
            suspend fun hash(path: String): ByteArray {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = if (isSaf(path)) resolver.openInputStream(uri(path)) else File(path).inputStream()
                (input ?: throw IOException("Document inaccessible pendant la vérification")).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest()
            }
            val matches = try { hash(source).contentEquals(hash(target)) }
                catch (e: Exception) { Diagnostic.event(Event.SHA256, Result.ERROR); throw e }
            Diagnostic.event(Event.SHA256, if (matches) Result.MATCH else Result.MISMATCH)
            check(matches) { "Copie non confirmée ou source modifiée : source conservée" }
        }
    }
}
