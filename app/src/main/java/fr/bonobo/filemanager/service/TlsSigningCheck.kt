package fr.bonobo.filemanager.service

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/** Android's TLS stack hashes first, then asks AndroidKeyStore for a raw ECDSA signature. */
object TlsSigningCheck {
    fun verify(privateKey: PrivateKey, publicKey: PublicKey) {
        val challenge = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(challenge)
        val signed = Signature.getInstance("NONEwithECDSA").run {
            initSign(privateKey)
            update(digest)
            sign()
        }
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signed)
        }
        check(valid) { "La clé TLS ne correspond pas au certificat" }
    }
}
