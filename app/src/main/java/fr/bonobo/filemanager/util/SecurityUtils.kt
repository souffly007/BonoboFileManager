package fr.bonobo.filemanager.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 16

    fun encrypt(file: File, password: String): File {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val encryptedFile = File(file.parent, "${file.name}.crypt")
        
        FileOutputStream(encryptedFile).use { fos ->
            fos.write(salt)
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(file).use { fis ->
                    file.inputStream().copyTo(cos)
                }
            }
        }
        return encryptedFile
    }

    fun decrypt(file: File, password: String): File {
        FileInputStream(file).use { fis ->
            val salt = ByteArray(SALT_SIZE)
            fis.read(salt)
            val iv = ByteArray(IV_SIZE)
            fis.read(iv)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
            val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val decryptedFile = File(file.parent, file.name.removeSuffix(".crypt"))
            
            FileOutputStream(decryptedFile).use { fos ->
                CipherInputStream(fis, cipher).use { cis ->
                    cis.copyTo(fos)
                }
            }
            return decryptedFile
        }
    }
}
