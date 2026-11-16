package fr.bonobo.filemanager.saf

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonobo.filemanager.util.SafFiles
import fr.bonobo.filemanager.util.IncomingFiles
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.io.File

class SafIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saf = SafFiles(context, renameWithoutReplacement = true) // private synthetic provider only

    @Test fun unavailableProviderIsNotReportedAsEmptyAndCanBeReopenedWithoutRevokingGrants() {
        val unavailable = root("unavailable")
        val before = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val failure = runCatching { SafFiles(context).list(unavailable) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("temporairement inaccessible"))
        assertTrue(runCatching { SafFiles(context).list(root()) }.isSuccess)
        assertEquals(before, context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
    }
    private fun root(id: String = "root"): String {
        val tree = DC.buildTreeDocumentUri("fr.bonobo.filemanager.test.documents", id)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val fixture = InstrumentationRegistry.getInstrumentation().context
            fixture.startActivity(Intent().setClassName(fixture.packageName, TestGrantActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                check(android.os.SystemClock.uptimeMillis() < deadline) { "La fixture n'a pas accordé l'accès temporaire" }
                android.os.SystemClock.sleep(20)
            }
        }
        return SafFiles.root(tree.toString())
    }
    @Test fun trashRestorationFilesFoldersLegacyAndConflicts() = runBlocking {
        val base = File(context.cacheDir, "trash-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val trash = File(base, "trash").apply { mkdir() }
        val safeParent = File(base, "safe-parent").apply { mkdir() }
        val restorer = fr.bonobo.filemanager.util.TrashRestorer(saf, trash,
            journal = File(base, "receipts"), alias = { safeParent }, originAllowed = { it.toPath().startsWith(base.toPath()) })
        try {
            val target = File(base, "restored.txt")
            val encoded = java.util.Base64.getUrlEncoder().encodeToString(target.path.toByteArray())
            val source = File(trash, "trash_${encoded}_#_restored.txt").apply { writeText("restoration verified") }
            restorer.restore(source.path)
            assertEquals(source.readText(), target.readText())
            assertTrue(source.exists())
            assertTrue(runCatching { restorer.restore(source.path) }.isFailure)
            assertEquals("restoration verified", target.readText())
            val old = File(trash, "trash_123_legacy.txt").apply { writeText("legacy") }
            assertTrue(runCatching { restorer.restore(old.path) }.isFailure)
            assertTrue(old.exists())
            val lostWrite = File(trash, "discard.txt").apply { writeText("must survive") }
            val rescue = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
            try {
                assertTrue(runCatching { restorer.restoreTo(lostWrite.path, rescue) }.isFailure)
                assertEquals("must survive", lostWrite.readText())
                restorer.restoreTo(old.path, rescue)
                assertTrue(old.exists())
                val output = File(base, "legacy-copy")
                saf.copy(saf.list(rescue).single().path, output.path)
                assertEquals("legacy", output.readText())
            } finally { saf.delete(rescue) }
            val folderTarget = File(base, "folder")
            val folderEncoded = java.util.Base64.getUrlEncoder().encodeToString(folderTarget.path.toByteArray())
            val folder = File(trash, "trash_${folderEncoded}_#_folder").apply { mkdir() }
            File(folder, "child").writeText("child intact")
            restorer.restore(folder.path)
            assertEquals("child intact", File(folderTarget, "child").readText())
            assertTrue(File(folder, "child").exists())
        } finally { base.deleteRecursively() }
    }
    @Test fun restoreRejectsSafTrashAliasAndUnknownProviderWithoutCopy() = runBlocking {
        val base = File(context.cacheDir, "alias-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val trash = File(base, "trash").apply { mkdir() }
        val source = File(trash, "legacy.txt").apply { writeText("preserve") }
        val uri = DC.buildDocumentUri("fr.bonobo.filemanager.test.documents", "root").toString()
        try {
            val alias = fr.bonobo.filemanager.util.TrashRestorer(saf, trash,
                journal = File(base, "receipts"), alias = { trash })
            assertTrue(runCatching { alias.restoreTo(source.path, uri) }.isFailure)
            val unknown = fr.bonobo.filemanager.util.TrashRestorer(saf, trash,
                journal = File(base, "receipts"), alias = { null })
            assertTrue(runCatching { unknown.restoreTo(source.path, uri) }.isFailure)
            assertEquals("preserve", source.readText())
            assertEquals(1, trash.listFiles()!!.size)
            assertFalse(File(base, "receipts").exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun localTransferReportsActualBytesAndSeparateVerificationPhase() = runBlocking {
        val base = File(context.cacheDir, "progress-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val source = File(base, "source")
        val target = File(base, "target")
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        source.outputStream().use { output -> repeat(51) { output.write(block) } }
        var bytes = 0L
        val phases = mutableListOf<fr.bonobo.filemanager.domain.transfer.TransferPhase>()
        try {
            kotlinx.coroutines.withContext(fr.bonobo.filemanager.domain.transfer.TransferEvents { event ->
                bytes += event.bytesDelta; phases += event.phase
            }) { saf.copy(source.path, target.path) }
            assertEquals(source.length(), bytes)
            assertEquals(source.length(), target.length())
            assertTrue(phases.contains(fr.bonobo.filemanager.domain.transfer.TransferPhase.VERIFYING))
            assertFalse(phases.contains(fr.bonobo.filemanager.domain.transfer.TransferPhase.DELETING))
            assertTrue(source.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun cancelledMoveDuringVerificationNeverEntersSourceDeletion() = runBlocking {
        val base = File(context.cacheDir, "cancel-verify-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val source = File(base, "source").apply { writeText("isolated source") }
        val operation = kotlinx.coroutines.Job()
        var deleting = false
        try {
            val result = runCatching {
                kotlinx.coroutines.withContext(operation + fr.bonobo.filemanager.domain.transfer.TransferEvents { event ->
                    if (event.phase == fr.bonobo.filemanager.domain.transfer.TransferPhase.VERIFYING) operation.cancel()
                    if (event.phase == fr.bonobo.filemanager.domain.transfer.TransferPhase.DELETING) deleting = true
                }) { saf.move(source.path, File(base, "target").path) }
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(deleting); assertEquals("isolated source", source.readText())
        } finally { operation.cancel(); base.deleteRecursively() }
    }

    @Test fun groupedMovePartialFailureKeepsFailedSources() = runBlocking {
        val base = File(context.cacheDir, "batch-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val sources = listOf("ok.txt", "conflict.txt", "folder").map { File(base, it) }
        sources[0].writeText("ok"); sources[1].writeText("retain")
        sources[2].mkdir(); File(sources[2], "child").writeText("nested")
        try {
            saf.copy(sources[1].path, SafFiles.destination(dir, "conflict.txt"))
            val outcomes = mutableListOf<Boolean>()
            fr.bonobo.filemanager.util.BatchTransfer.run(sources, { _, _ -> },
                { source -> runCatching { saf.move(source.path, SafFiles.destination(dir, source.name)) } },
                { _, result -> outcomes += result.isSuccess })
            assertEquals(listOf(false, false, false), outcomes)
            assertTrue(sources.all { it.exists() })
            assertEquals("retain", sources[1].readText())
            assertEquals(3, saf.list(dir).size)
        } finally { base.deleteRecursively(); saf.delete(dir) }
    }

    @Test fun usbOperationsAndNameConflicts() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val local = File(context.cacheDir, "source-${java.util.UUID.randomUUID()}.txt").apply { writeText("USB test é") }
        val output = File(context.cacheDir, "copy-${java.util.UUID.randomUUID()}.txt")
        try {
            saf.copy(local.path, SafFiles.destination(dir, "hello.txt"))
            val entry = saf.list(dir).single()
            assertTrue(runCatching { saf.copy(local.path, SafFiles.destination(dir, "hello.txt")) }.isFailure)
            saf.copy(entry.path, output.path)
            assertEquals(local.readText(), output.readText())
            val renamed = saf.rename(entry.path, "renamed.txt")
            assertNotEquals(entry.path, renamed)
            assertEquals(renamed, saf.currentReference(entry.path))
            assertEquals("renamed.txt", saf.list(dir).single().name)
            val moved = File(context.cacheDir, "moved-${java.util.UUID.randomUUID()}.txt")
            val moveResult = runCatching { saf.move(saf.list(dir).single().path, moved.path) }
            assertTrue(moveResult.isFailure) // app cache volume is not qualified for destructive finalization
            assertEquals(local.readText(), moved.readText()); moved.delete()
            assertEquals(1, saf.list(dir).size)
            val nested = saf.create(dir, "nested", DC.Document.MIME_TYPE_DIR)
            assertTrue(runCatching { saf.copy(dir, SafFiles.destination(nested, "loop")) }.isFailure)
            saf.copy(local.path, SafFiles.destination(nested, "child.txt"))
            val treeCopy = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
            try {
                saf.copy(nested, SafFiles.destination(treeCopy, "copied"))
                assertEquals("child.txt", saf.list(saf.list(treeCopy).single().path).single().name)
            } finally { saf.delete(treeCopy) }
        } finally { local.delete(); output.delete(); saf.delete(dir) }
    }
    @Test fun readOnlyDisconnectedAndFailedWrites() = runBlocking {
        assertTrue(runCatching { saf.create(root("readonly"), "forbidden", DC.Document.MIME_TYPE_DIR) }.isFailure)
        assertTrue(runCatching { saf.list(root("missing")) }.exceptionOrNull()?.message?.contains("Support") == true)
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val local = File(context.cacheDir, "failure-${java.util.UUID.randomUUID()}.txt").apply { writeText("source intacte") }
        try {
            assertTrue(runCatching { saf.copy(local.path, SafFiles.destination(dir, "fail.txt")) }.isFailure)
            assertEquals("source intacte", local.readText())
            assertTrue(saf.list(dir).isEmpty())
        } finally { local.delete(); saf.delete(dir) }
    }
    @Test fun interruptedMoveKeepsSourceAndRemovesPartialDestination() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val doc = saf.create(dir, "truncated.txt", "text/plain")
        context.contentResolver.openOutputStream(Uri.parse(doc))!!.use { it.write("partial".toByteArray()) }
        val dest = File(context.cacheDir, "interrupted-${java.util.UUID.randomUUID()}.txt")
        try {
            assertTrue(runCatching { saf.move(doc, dest.path) }.isFailure)
            assertFalse(dest.exists())
            assertEquals("truncated.txt", saf.list(dir).single().name)
        } finally { dest.delete(); saf.delete(dir) }
    }
    @Test fun contentStreamsAndUnsupportedSchemes() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val doc = saf.create(dir, "received.txt", "text/plain")
        context.contentResolver.openOutputStream(Uri.parse(doc))!!.use { it.write("partagé".toByteArray()) }
        try {
            val incoming = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(doc))
            assertEquals(listOf(Uri.parse(doc)), IncomingFiles.uris(incoming))
            val snapshot = IncomingFiles.snapshot(context, Uri.parse(doc))
            assertEquals("partagé", snapshot.readText()); snapshot.delete()
            assertTrue(runCatching { IncomingFiles.uris(Intent(Intent.ACTION_VIEW, Uri.parse("file:///etc/passwd"))) }.isFailure)
            assertTrue(runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse(doc), Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isFailure)
        } finally { saf.delete(dir) }
    }
    @Test fun silentLossIncorrectCreationAndInaccessibleDestinationKeepSource() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val existing = saf.create(dir, "existing.txt", "text/plain")
        context.contentResolver.openOutputStream(Uri.parse(existing))!!.use { it.write("intact".toByteArray()) }
        val local = File(context.cacheDir, "verify-${System.nanoTime()}.txt").apply { writeText("source conservée") }
        try {
            for (name in listOf("discard.txt", "wrong-id.txt", "phantom.txt", "unreadable.txt", "fail.txt")) {
                assertTrue(name, runCatching { saf.move(local.path, SafFiles.destination(dir, name)) }.isFailure)
                assertEquals("source conservée", local.readText())
                assertEquals("intact", context.contentResolver.openInputStream(Uri.parse(existing))!!.bufferedReader().use { it.readText() })
            }
        } finally { local.delete(); saf.delete(dir) }
    }

    @Test fun repositoryReopenRecursiveCopiesAndMovesBothDirections() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val local = File(context.cacheDir, "tree-${System.nanoTime()}").apply { mkdirs() }
        File(local, "nested").mkdir()
        File(local, "nested/data.txt").writeText("persistent content é")
        val dbName = "saf-test-${System.nanoTime()}.db"
        fun database() = androidx.room.Room.databaseBuilder(context, fr.bonobo.filemanager.data.local.AppDatabase::class.java, dbName).build()
        fun repository(db: fr.bonobo.filemanager.data.local.AppDatabase) = fr.bonobo.filemanager.data.repository.FileRepositoryImpl(db.fileDao(), db.favoriteDao(), db.historyDao(), db.bookmarkDao(), context)
        var db = database()
        val back = File(context.cacheDir, "back-${System.nanoTime()}")
        try {
            assertTrue(repository(db).copyFile(local.path, SafFiles.destination(dir, "copied")).isSuccess)
            db.close(); db = database()
            val reopened = repository(db)
            val copied = reopened.getFiles(dir).single()
            val nested = reopened.getFiles(copied.path).single()
            assertEquals("persistent content é", context.contentResolver.openInputStream(Uri.parse(reopened.getFiles(nested.path).single().path))!!.bufferedReader().use { it.readText() })
            assertTrue(reopened.moveFile(local.path, SafFiles.destination(dir, "moved")).isFailure)
            assertTrue(local.exists())
            val moved = reopened.getFiles(dir).single { it.name == "moved" }
            assertTrue(reopened.moveFile(moved.path, back.path).isFailure)
            assertEquals("persistent content é", File(back, "nested/data.txt").readText())
            assertTrue(reopened.copyFile(copied.path, SafFiles.destination(dir, "usb-to-usb")).isSuccess)
            db.close(); db = database()
            assertEquals(setOf("copied", "moved", "usb-to-usb"), repository(db).getFiles(dir).map { it.name }.toSet())
        } finally { db.close(); context.deleteDatabase(dbName); local.deleteRecursively(); back.deleteRecursively(); saf.delete(dir) }
    }

    @Test fun cancellationDuringTransferPreservesSource() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val doc = saf.create(dir, "slow.txt", "text/plain")
        context.contentResolver.openOutputStream(Uri.parse(doc))!!.use { it.write(ByteArray(256 * 1024) { 42 }) }
        val dest = File(context.cacheDir, "cancel-${System.nanoTime()}.txt")
        try {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { saf.move(doc, dest.path) }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (!dest.exists()) {
                check(android.os.SystemClock.uptimeMillis() < deadline)
                kotlinx.coroutines.delay(10)
            }
            kotlinx.coroutines.delay(50)
            job.cancel(); job.join()
            assertTrue(job.isCancelled)
            assertFalse(dest.exists())
            assertEquals("slow.txt", saf.list(dir).single().name)
        } finally { dest.delete(); saf.delete(dir) }
    }

    @Test fun cancellationInBatchKeepsCurrentAndUnstartedSources() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val doc = saf.create(dir, "slow.txt", "text/plain")
        context.contentResolver.openOutputStream(Uri.parse(doc))!!.use { it.write(ByteArray(256 * 1024) { 42 }) }
        val dest = File(context.cacheDir, "batch-cancel-${System.nanoTime()}.txt")
        val unstarted = File(context.cacheDir, "unstarted-${System.nanoTime()}").apply { writeText("intact") }
        val started = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                fr.bonobo.filemanager.util.BatchTransfer.run(listOf(doc, unstarted.path),
                    { _, item -> started += item },
                    { path -> saf.move(path, dest.path); Result.success(Unit) }, { _, _ -> })
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (!dest.exists()) {
                check(android.os.SystemClock.uptimeMillis() < deadline)
                kotlinx.coroutines.delay(10)
            }
            job.cancel(); job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(doc), started.toList())
            assertTrue(saf.list(dir).any { it.path == doc })
            assertEquals("intact", unstarted.readText())
            assertFalse(dest.exists())
        } finally { dest.delete(); unstarted.delete(); saf.delete(dir) }
    }

    @Test fun revokedOrInvalidatedPermissionIsExplicitAndCanBeRetried() {
        assertTrue(runCatching { saf.list(root("denied")) }.exceptionOrNull()?.message?.contains("Sélectionnez à nouveau") == true)
        assertTrue(runCatching { saf.list(root("missing")) }.isFailure)
        assertNotNull(SafFiles(context).list(root()))
        val revocable = root("revocable")
        assertNotNull(saf.list(revocable))
        val fixture = InstrumentationRegistry.getInstrumentation().context
        fixture.startActivity(Intent().setClassName(fixture.packageName, TestGrantActivity::class.java.name)
            .putExtra("revoke", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val tree = DC.buildTreeDocumentUri("fr.bonobo.filemanager.test.documents", "revocable")
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            check(android.os.SystemClock.uptimeMillis() < deadline)
            android.os.SystemClock.sleep(20)
        }
        assertTrue(runCatching { SafFiles(context).list(revocable) }.isFailure)
        assertNotNull(SafFiles(context).list(root("revocable")))
    }

    @Test fun interruptedProviderWriteAndFailedRecursiveMoveKeepSources() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val local = File(context.cacheDir, "write-error-${System.nanoTime()}").apply { mkdirs() }
        val file = File(local, "broken-pipe.txt").apply { writeBytes(ByteArray(256 * 1024) { 7 }) }
        try {
            assertTrue(runCatching { saf.move(file.path, SafFiles.destination(dir, file.name)) }.isFailure)
            assertEquals(256 * 1024L, file.length())
            File(local, "discard.txt").writeText("recursive source")
            assertTrue(runCatching { saf.move(local.path, SafFiles.destination(dir, "recursive")) }.isFailure)
            assertTrue(file.exists())
            assertEquals("recursive source", File(local, "discard.txt").readText())
        } finally { local.deleteRecursively(); saf.delete(dir) }
    }

    @Test fun beta19GroupedRestoreToChosenSafFolderPreservesEverySource() = runBlocking {
        val base = File(context.cacheDir, "beta19-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val trash = File(base, "trash").apply { mkdir() }
        val safeParent = File(base, "safe-parent").apply { mkdir() }
        val restorer = fr.bonobo.filemanager.util.TrashRestorer(saf, trash,
            journal = File(base, "receipts"), alias = { safeParent })
        val destination = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val sources = listOf("first.txt", "conflict.txt", "last.txt").map { File(trash, it).apply { writeText("source-$it") } }
        try {
            saf.copy(sources[1].path, SafFiles.destination(destination, "conflict.txt"))
            val results = mutableListOf<Boolean>()
            fr.bonobo.filemanager.util.BatchTransfer.run(sources, { _, _ -> },
                { source -> runCatching { restorer.restoreTo(source.path, destination) } },
                { _, result -> results += result.isSuccess })
            assertEquals(listOf(true, false, true), results)
            assertTrue(sources.all { it.exists() })
            assertEquals(3, saf.list(destination).size)
            assertTrue(runCatching { restorer.restoreTo(sources[0].path, destination) }.exceptionOrNull() is fr.bonobo.filemanager.util.AlreadyRestoredException)
        } finally { base.deleteRecursively(); saf.delete(destination) }
    }

    @Test fun beta191SafCopyAndMoveUseConcreteDownloadDestination() = runBlocking {
        val base = File(context.cacheDir, "beta191-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val download = File(base, "Download").apply { mkdir() }
        val previous = File(base, "previous").apply { mkdir() }
        val seed = File(base, "seed.txt").apply { writeText("synthetic USB source") }
        val usb = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val factory = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
            Beta191TransferDependencies::class.java).transferEngines()
        try {
            for (operation in listOf(fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY,
                fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE)) {
                val name = "${operation.name}.txt"
                saf.copy(seed.path, SafFiles.destination(usb, name))
                val item = saf.list(usb).single { it.name == name }
                val source = fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(item)
                val destination = fr.bonobo.filemanager.data.transfer.TransferEngineFactory.storage(download.path)
                val result = runCatching { factory.local().execute(source, destination, operation) }
                assertEquals(operation == fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE, result.isFailure)
                assertEquals(seed.readText(), File(download, name).readText())
                assertFalse(File(previous, name).exists())
                assertTrue(saf.list(usb).any { it.name == name })
            }
            val retained = saf.list(usb).first()
            val forbidden = fr.bonobo.filemanager.data.transfer.TransferEngineFactory.storage(root("readonly"))
            assertTrue(runCatching { factory.local().execute(
                fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(retained), forbidden,
                fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE) }.isFailure)
            assertTrue(saf.list(usb).any { it.path == retained.path })
        } finally { base.deleteRecursively(); saf.delete(usb) }
    }

    @Test fun beta192FactoryAcceptsOpaqueDestinationsAndVerifiesBeforeMove() = runBlocking {
        val base = File(context.cacheDir, "beta192-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val usb = saf.create(root("opaque"), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val factory = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
            Beta191TransferDependencies::class.java).transferEngines()
        try {
            assertNull(saf.localAlias(usb))
            for (directory in listOf(false, true)) {
                for (move in listOf(false, true)) {
                    val source = File(base, "source-$directory-$move")
                    if (directory) { source.mkdir(); File(source, "child").writeText("verified fixture") }
                    else source.writeText("verified fixture")
                    val item = with(fr.bonobo.filemanager.util.FileUtils) { source.toFileItem() }
                    val result = runCatching { factory.local().execute(fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(item),
                        fr.bonobo.filemanager.domain.transfer.StorageRef.SafDocument(usb),
                        if (move) fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE else fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY) }
                    assertEquals(move, result.isFailure)
                    assertTrue(source.exists())
                    val copied = saf.list(usb).single { it.name == source.name }
                    val fingerprint = fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(saf.integrityAccess, copied.path)
                    assertNotNull(fingerprint)
                }
            }
            val other = saf.create(root("opaque"), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
            try {
                val source = saf.list(usb).single { it.name == "source-true-false" }
                factory.local().execute(fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(source),
                    fr.bonobo.filemanager.domain.transfer.StorageRef.SafDocument(other),
                    fr.bonobo.filemanager.domain.transfer.TransferOperation.COPY)
                val copied = saf.list(other).single()
                assertEquals(fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(saf.integrityAccess, source.path),
                    fr.bonobo.filemanager.util.IntegrityVerifier.fingerprint(saf.integrityAccess, copied.path))
                assertTrue(runCatching { factory.local().execute(
                    fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(source),
                    fr.bonobo.filemanager.domain.transfer.StorageRef.SafDocument(other),
                    fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE) }.isFailure)
                assertTrue(saf.list(usb).any { it.path == source.path })
            } finally { saf.delete(other) }
            for (name in listOf("discard.txt", "unreadable.txt", "fail.txt", "broken-pipe.txt")) {
                val source = File(base, name).apply { writeBytes(ByteArray(256 * 1024) { 17 }) }
                val item = with(fr.bonobo.filemanager.util.FileUtils) { source.toFileItem() }
                assertTrue(runCatching { factory.local().execute(
                    fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(item),
                    fr.bonobo.filemanager.domain.transfer.StorageRef.SafDocument(usb),
                    fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE) }.isFailure)
                assertEquals(256 * 1024L, source.length())
            }
            val local = File(base, "denied").apply { writeText("retain") }
            val item = with(fr.bonobo.filemanager.util.FileUtils) { local.toFileItem() }
            assertTrue(runCatching { factory.local().execute(
                fr.bonobo.filemanager.data.transfer.TransferEngineFactory.source(item),
                fr.bonobo.filemanager.domain.transfer.StorageRef.SafDocument(root("readonly")),
                fr.bonobo.filemanager.domain.transfer.TransferOperation.MOVE) }.isFailure)
            assertTrue(local.exists())
        } finally { base.deleteRecursively(); saf.delete(usb) }
    }

    @Test fun beta192DeleteCapabilitiesAndMoveFailureAreExplicitForFilesAndFolders() = runBlocking {
        val usb = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val base = File(context.cacheDir, "beta192-delete-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (directory in listOf(false, true)) {
                val mime = if (directory) DC.Document.MIME_TYPE_DIR else "text/plain"
                val ok = saf.create(usb, "ok-$directory", mime)
                saf.delete(ok)
                assertFalse(saf.list(usb).any { it.path == ok })
                val refused = saf.create(usb, "nodelete-$directory", mime)
                assertTrue(runCatching { saf.delete(refused) }.isFailure)
                val failed = saf.create(usb, "delete-error-$directory", mime)
                val target = File(base, "copied-$directory")
                val error = runCatching { saf.move(failed, target.path) }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty().contains("Copie effectuée, déplacement incomplet"))
                assertTrue(target.exists())
                assertTrue(saf.list(usb).any { it.path == failed })
            }
        } finally { base.deleteRecursively(); saf.delete(usb) }
    }

    @Test fun beta193PositiveDeleteAcknowledgementWithoutDisappearanceIsIndeterminate() {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        try {
            val file = saf.create(dir, "delete-noeffect.txt", "text/plain")
            assertEquals(fr.bonobo.filemanager.util.SafMutations.Deletion.INDETERMINATE, saf.deletionResult(file).state)
            assertTrue(saf.list(dir).any { it.path == file })
            assertTrue(runCatching { saf.delete(file) }.isFailure)
        } finally { saf.delete(dir) }
    }

    @Test fun beta193PublicationRefusalCleansOnlyTrackedStageAndPreservesExistingFile() = runBlocking {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        val base = File(context.cacheDir, "beta193-${java.util.UUID.randomUUID()}").apply { mkdir() }
        try {
            val source = File(base, "source").apply { writeText("synthetic source") }
            saf.copy(source.path, SafFiles.destination(dir, "existing.txt"))
            val existing = saf.list(dir).single().path
            assertTrue(runCatching { saf.copy(source.path, SafFiles.destination(dir, "publication-refused.txt")) }.isFailure)
            assertEquals("synthetic source", source.readText())
            assertEquals(listOf(existing), saf.list(dir).map { it.path })
            assertTrue(runCatching { SafFiles(context).apply { list(dir) }.rename(existing, "other.txt") }.isFailure)
            assertEquals("existing.txt", saf.list(dir).single().name)
        } finally { base.deleteRecursively(); saf.delete(dir) }
    }

    @Test fun beta193PartialRecursiveDeletionCannotBecomeSuccess() {
        val dir = saf.create(root(), java.util.UUID.randomUUID().toString(), DC.Document.MIME_TYPE_DIR)
        try {
            val folder = saf.create(dir, "delete-partial-folder", DC.Document.MIME_TYPE_DIR)
            saf.create(folder, "one", "text/plain"); saf.create(folder, "two", "text/plain")
            assertTrue(runCatching { saf.delete(folder) }.isFailure)
            assertEquals(1, saf.list(folder).size)
            assertTrue(saf.list(dir).any { it.path == folder })
        } finally { saf.delete(dir) }
    }
}


@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface Beta191TransferDependencies {
    fun transferEngines(): fr.bonobo.filemanager.data.transfer.TransferEngineFactory
}
