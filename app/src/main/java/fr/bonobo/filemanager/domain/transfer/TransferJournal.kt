package fr.bonobo.filemanager.domain.transfer

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** One bounded private summary. No credentials, URLs, document IDs or automatic replay. */
class TransferJournal(private val file: File) {
    fun write(state: TransferState) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File.createTempFile("transfer-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                Properties().apply {
                    setProperty("version", "1"); setProperty("id", state.id)
                    setProperty("acknowledged", state.acknowledged.toString())
                    setProperty("active", state.active.toString()); setProperty("phase", state.phase.name)
                    setProperty("operation", state.operation.name)
                    setProperty("roots", state.totalRoots.toString()); setProperty("processed", state.processedRoots.toString())
                    setProperty("files", state.completedFiles.toString()); setProperty("bytes", state.bytes.toString())
                    setProperty("errors", state.results.count { it.status != ItemStatus.DONE }.toString())
                }.store(output, null)
                output.fd.sync()
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    fun read(): TransferState {
        if (!file.exists()) return TransferState()
        return try {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            require(p.getProperty("version") == "1")
            val active = p.getProperty("active").toBooleanStrict()
            val phase = TransferPhase.valueOf(p.getProperty("phase"))
            TransferState(id = p.getProperty("id"), active = false,
                acknowledged = !active && p.getProperty("acknowledged", "false").toBoolean(),
                phase = if (active) TransferPhase.INTERRUPTED else phase,
                operation = TransferOperation.valueOf(p.getProperty("operation")),
                totalRoots = p.getProperty("roots").toInt(), processedRoots = p.getProperty("processed").toInt(),
                completedFiles = p.getProperty("files").toLong(), bytes = p.getProperty("bytes").toLong(),
                notice = if (active) "L’application s’est arrêtée pendant un transfert. Contrôlez les sources et destinations ; aucune reprise ni suppression automatique."
                else "Dernier lot : ${p.getProperty("errors")} élément(s) non confirmé(s). Les détails étaient disponibles avant fermeture ; aucun transfert relancé.")
        } catch (_: Exception) { TransferState(id = "unreadable", phase = TransferPhase.INTERRUPTED,
            notice = "Journal précédent illisible : contrôlez les destinations ; aucune reprise automatique.") }
    }
}
