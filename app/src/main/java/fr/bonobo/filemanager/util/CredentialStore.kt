package fr.bonobo.filemanager.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor() {
    private val alias = "bonobo.remote.credentials.v1"

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Clé des connexions indisponible : recréez la connexion" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(encoded: String): String {
        val data = Base64.getDecoder().decode(encoded)
        require(data.size >= 28) { "Identifiant chiffré invalide" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}
