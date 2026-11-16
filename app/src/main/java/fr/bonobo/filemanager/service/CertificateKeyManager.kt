package fr.bonobo.filemanager.service

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Uses the key handle directly: AndroidKeyStore keys are deliberately not exportable. */
class CertificateKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>
) : X509ExtendedKeyManager() {
    override fun getPrivateKey(alias: String?) = if (alias == this.alias) key else null
    override fun getCertificateChain(alias: String?) = if (alias == this.alias) chain.copyOf() else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
        if (keyType == "EC") arrayOf(alias) else null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
        getServerAliases(keyType, issuers)?.firstOrNull()
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        getServerAliases(keyType, issuers)?.firstOrNull()
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
