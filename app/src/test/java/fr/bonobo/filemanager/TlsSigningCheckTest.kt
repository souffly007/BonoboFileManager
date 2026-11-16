package fr.bonobo.filemanager

import fr.bonobo.filemanager.service.TlsSigningCheck
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertThrows
import org.junit.Test

class TlsSigningCheckTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun signsPrehashedTlsDigestWithoutHashingTwice() {
        val pair = keyPair()
        TlsSigningCheck.verify(pair.private, pair.public)
    }
    @Test fun mismatchedCertificateFailsBeforeServerStarts() {
        assertThrows(IllegalStateException::class.java) {
            TlsSigningCheck.verify(keyPair().private, keyPair().public)
        }
    }
}
