package fr.bonobo.filemanager.service

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Persistent, per-installation TLS identity; private key never leaves AndroidKeyStore. */
object FtpsIdentity {
    // Key authorizations cannot be broadened on an existing AndroidKeyStore key.
    // A new TLS-only alias migrates installations that already generated v1.
    private const val ALIAS = "bonobo_ftps_identity_v2"
    data class Identity(val tls: ContextSslConfiguration, val fingerprint: String)

    fun load(): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt signs an already-computed TLS digest with NONEwithECDSA.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Bonobo FTPS"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(now - 86_400_000L))
                    .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000L))
                    .build())
            }.generateKeyPair()
        }
        val key = store.getKey(ALIAS, null) as PrivateKey
        val chain = store.getCertificateChain(ALIAS).map { it as X509Certificate }.toTypedArray()
        chain[0].checkValidity()
        try {
            TlsSigningCheck.verify(key, chain[0].publicKey)
        } catch (e: Exception) {
            throw IllegalStateException("La clé Android ne peut pas signer pour TLS (${e.javaClass.simpleName}).", e)
        }
        val manager = CertificateKeyManager(ALIAS, key, chain)
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        return Identity(ContextSslConfiguration(context), fingerprint)
    }
}
