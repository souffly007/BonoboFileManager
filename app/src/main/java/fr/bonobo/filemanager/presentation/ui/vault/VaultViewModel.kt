package fr.bonobo.filemanager.presentation.ui.vault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.data.local.SettingsKeys
import fr.bonobo.filemanager.data.local.settingsDataStore
import fr.bonobo.filemanager.util.SafeFiles
import fr.bonobo.filemanager.util.SecurityUtils
import fr.bonobo.filemanager.util.VaultBiometricStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

data class VaultState(
    val configured: Boolean? = null,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val files: List<File> = emptyList(),
    val message: String? = null,
    val biometricEnabled: Boolean = false
)

@HiltViewModel
class VaultViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(VaultState())
    val state = mutable.asStateFlow()
    private var secret: CharArray? = null
    private var generation = 0
    private val vault = File(context.noBackupFilesDir, "encrypted_vault")
    private val work = File(context.noBackupFilesDir, "vault_work")
    private var pending: (suspend (String) -> String)? = null

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(vault.isDirectory || vault.mkdirs()) { "Coffre inaccessible" }
                    // Private plaintext work files from an interrupted export are never retained.
                    work.deleteRecursively()
                    check(work.mkdirs()) { "Dossier temporaire inaccessible" }
                }
                val stored = context.settingsDataStore.data.first()[SettingsKeys.VAULT_PASSWORD]
                val biometric = context.settingsDataStore.data.first()[SettingsKeys.VAULT_BIOMETRIC_ENABLED] == true
                mutable.update { it.copy(configured = stored != null, biometricEnabled = biometric) }
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Impossible de charger le coffre : ${e.message}") }
            }
        }
    }

    fun unlock(password: String, confirmation: String) {
        if (mutable.value.busy || mutable.value.configured == null) return
        val epoch = generation
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stored = context.settingsDataStore.data.first()[SettingsKeys.VAULT_PASSWORD]
                    if (stored == null) {
                        require(password.length >= 8 && password == confirmation) {
                            "Au moins 8 caractères et une confirmation identique sont nécessaires"
                        }
                        require(vault.listFiles()?.none { it.name.endsWith(".crypt") } == true) {
                            "Configuration absente : conserver les fichiers, ne pas recréer de mot de passe"
                        }
                    } else {
                        require(SecurityUtils.verifyPassword(password, stored)) { "Mot de passe incorrect" }
                    }
                    if (stored == null || !stored.startsWith("v1$")) {
                        val hashed = SecurityUtils.hashPassword(password)
                        context.settingsDataStore.edit {
                            check(it[SettingsKeys.VAULT_PASSWORD] == stored) { "Configuration modifiée, réessayez" }
                            it[SettingsKeys.VAULT_PASSWORD] = hashed
                        }
                    }
                }
                if (epoch == generation) {
                    val files = withContext(Dispatchers.IO) { listFiles() }
                    if (epoch == generation) {
                        secret?.fill('\u0000')
                        secret = password.toCharArray()
                        mutable.update { it.copy(unlocked = true, configured = true, files = files) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = e.message ?: "Déverrouillage impossible") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
            if (mutable.value.unlocked) {
                val action = pending
                pending = null
                if (action != null) perform(action)
            }
        }
    }

    fun lock() {
        generation++
        secret?.fill('\u0000')
        secret = null
        mutable.update { it.copy(unlocked = false, files = emptyList(), message = null) }
    }

    fun unlockWithBiometric() {
        if (mutable.value.busy || mutable.value.configured != true || !mutable.value.biometricEnabled) return
        viewModelScope.launch {
            try {
                val encoded = context.settingsDataStore.data.first()[SettingsKeys.VAULT_BIOMETRIC_SECRET]
                    ?: error("Déverrouillage biométrique non configuré")
                val password = withContext(Dispatchers.IO) { VaultBiometricStore.decrypt(encoded) }
                val stored = context.settingsDataStore.data.first()[SettingsKeys.VAULT_PASSWORD]
                require(stored != null && SecurityUtils.verifyPassword(password, stored)) { "Secret biométrique invalide" }
                val files = withContext(Dispatchers.IO) { listFiles() }
                secret?.fill('\u0000')
                secret = password.toCharArray()
                mutable.update { it.copy(unlocked = true, files = files, message = null) }
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Déverrouillage biométrique impossible : ${e.message}") }
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        val password = secret?.concatToString() ?: return
        viewModelScope.launch {
            try {
                if (enabled) {
                    val encrypted = withContext(Dispatchers.IO) { VaultBiometricStore.encrypt(password) }
                    context.settingsDataStore.edit {
                        it[SettingsKeys.VAULT_BIOMETRIC_SECRET] = encrypted
                        it[SettingsKeys.VAULT_BIOMETRIC_ENABLED] = true
                    }
                } else {
                    context.settingsDataStore.edit {
                        it.remove(SettingsKeys.VAULT_BIOMETRIC_SECRET)
                        it[SettingsKeys.VAULT_BIOMETRIC_ENABLED] = false
                    }
                }
                mutable.update { it.copy(biometricEnabled = enabled, message = if (enabled) "Déverrouillage biométrique activé" else "Déverrouillage biométrique désactivé") }
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Configuration biométrique impossible : ${e.message}") }
            }
        }
    }

    private fun listFiles(): List<File> = (vault.listFiles() ?: error("Coffre inaccessible"))
        .filter { it.isFile && it.name.endsWith(".crypt") && !Files.isSymbolicLink(it.toPath()) }
        .sortedBy { it.name.lowercase() }

    private fun destination(name: String): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "_").take(120)
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "fichier"
        var target = SafeFiles.child(vault, "$safe.crypt")
        var index = 1
        while (SafeFiles.exists(target)) target = SafeFiles.child(vault, "$safe (${index++}).crypt")
        return target
    }

    fun importDocument(uri: Uri) = perform { password ->
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "fichier"
        val target = destination(name)
        resolver.openInputStream(uri)?.use { SecurityUtils.encryptTo(it, target, password) }
            ?: error("Impossible d'ouvrir le document")
        "Fichier chiffré et ajouté. L'original reste à son emplacement."
    }

    fun exportDocument(file: File, uri: Uri) = perform { password ->
        validateFile(file)
        val temporary = File(work, "export-${java.util.UUID.randomUUID()}")
        try {
            SecurityUtils.decryptTo(file, temporary, password)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: error("Impossible d'écrire le document")
        } finally {
            temporary.delete()
        }
        "Copie déchiffrée exportée à l'emplacement choisi."
    }

    fun delete(file: File) = perform {
        validateFile(file)
        check(file.delete()) { "Suppression impossible" }
        "Fichier supprimé du coffre."
    }

    fun importLegacy() = perform { password ->
        val legacy = File(Environment.getExternalStorageDirectory(), ".bonobo_vault")
        require(legacy.isDirectory && !Files.isSymbolicLink(legacy.toPath())) { "Aucun ancien coffre trouvé" }
        var count = 0
        legacy.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .onFail { _, error -> throw error }.forEach { file ->
                if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                    file.inputStream().use { SecurityUtils.encryptTo(it, destination(file.name), password) }
                    count++
                }
            }
        "$count fichier(s) copié(s) et chiffré(s). Les anciens originaux sont conservés : vérifiez puis supprimez-les si souhaité."
    }

    private fun validateFile(file: File) {
        require(file.canonicalFile.parentFile == vault.canonicalFile &&
            !Files.isSymbolicLink(file.toPath()) && file.isFile) { "Fichier de coffre invalide" }
    }

    private fun perform(action: suspend (String) -> String) {
        if (mutable.value.busy) {
            mutable.update { it.copy(message = "Une opération est déjà en cours") }
            return
        }
        val password = secret?.concatToString()
        if (password == null) {
            pending = action
            mutable.update { it.copy(message = "Déverrouillez le coffre pour terminer l'opération choisie") }
            return
        }
        val epoch = generation
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action(password) }
                val files = withContext(Dispatchers.IO) { listFiles() }
                mutable.update { it.copy(message = message, files = if (epoch == generation) files else emptyList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Opération interrompue : ${e.message}. Les sources sont conservées.") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        lock()
        pending = null
        super.onCleared()
    }
}
