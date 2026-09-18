package fr.bonobo.filemanager.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import java.io.InputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_LENGTH_BITS = 128
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte())

    fun encrypt(file: File, password: String): File {
        require(password.length >= 8) { "Mot de passe de 8 caractères minimum" }
        require(file.isFile) { "Fichier source introuvable" }
        val destination = File(file.parentFile, "${file.name}.crypt")
        file.inputStream().use { encryptTo(it, destination, password) }
        return destination
    }

    fun encryptTo(input: InputStream, destination: File, password: String) {
        require(password.isNotEmpty()) { "Mot de passe vide" }
        require(!SafeFiles.exists(destination)) { "Le fichier chiffré existe déjà" }
        val temporary = File.createTempFile(".bonobo-", ".tmp", destination.parentFile)
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                updateAAD(MAGIC)
            }
            FileOutputStream(temporary).use { output ->
                output.write(MAGIC)
                output.write(salt)
                output.write(iv)
                CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted) }
            }
            SafeFiles.publish(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    fun decrypt(file: File, password: String): File {
        require(file.name.endsWith(".crypt")) { "Extension .crypt attendue" }
        val destination = File(file.parentFile, file.name.removeSuffix(".crypt"))
        decryptTo(file, destination, password)
        return destination
    }

    fun decryptTo(file: File, destination: File, password: String) {
        require(file.isFile) { "Fichier chiffré introuvable" }
        require(password.isNotEmpty()) { "Mot de passe vide" }
        require(!SafeFiles.exists(destination)) { "Le fichier de destination existe déjà" }
        val temporary = File.createTempFile(".bonobo-", ".tmp", destination.parentFile)
        try {
            FileInputStream(file).use { input ->
                val magic = input.readExact(MAGIC.size)
                require(MessageDigest.isEqual(magic, MAGIC)) {
                    "Ancien format ou fichier inconnu : conserver l'original pour récupération"
                }
                val salt = input.readExact(SALT_SIZE)
                val iv = input.readExact(IV_SIZE)
                val cipher = Cipher.getInstance(ALGORITHM).apply {
                    init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                    updateAAD(MAGIC)
                }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        cipher.update(buffer, 0, count)?.let { output.write(it) }
                    }
                    // Explicit doFinal: authentication failure must never be swallowed by a stream.
                    output.write(cipher.doFinal())
                }
            }
            SafeFiles.publish(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val hash = deriveBytes(password, salt)
        return "v1${'$'}$ITERATIONS${'$'}${Base64.getEncoder().encodeToString(salt)}${'$'}${Base64.getEncoder().encodeToString(hash)}"
    }

    fun verifyPassword(password: String, encoded: String): Boolean {
        if (!encoded.startsWith("v1$")) {
            return MessageDigest.isEqual(password.toByteArray(), encoded.toByteArray())
        }
        return try {
            val parts = encoded.split('$')
            if (parts.size != 4 || parts[0] != "v1" || parts[1].toInt() != ITERATIONS) {
                false
            } else {
                val salt = Base64.getDecoder().decode(parts[2])
                val expected = Base64.getDecoder().decode(parts[3])
                salt.size == SALT_SIZE && expected.size == KEY_LENGTH / 8 &&
                    MessageDigest.isEqual(expected, deriveBytes(password, salt))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deriveKey(password: String, salt: ByteArray) =
        SecretKeySpec(deriveBytes(password, salt), "AES")

    private fun deriveBytes(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun FileInputStream.readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            require(count >= 0) { "Fichier chiffré tronqué" }
            offset += count
        }
        return bytes
    }
}
