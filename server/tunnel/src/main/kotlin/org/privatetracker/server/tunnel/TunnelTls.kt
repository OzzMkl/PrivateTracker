package org.privatetracker.server.tunnel

import org.privatetracker.core.domain.model.keyFingerprint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** The fingerprint of a certificate's key, as people read it out: `3F9A-01BC-77D2-E410`. */
fun X509Certificate.keyFingerprint(): String? = keyFingerprint(Base64.getEncoder().encodeToString(publicKey.encoded))

/** The relay showed a key other than the one this server was told to trust. */
class RelayKeyMismatchException : CertificateException("The relay's key is not the one configured")

/**
 * Trusts a relay by the fingerprint of its key and nothing else, as trackers trust servers: relays use
 * self-signed certificates, so no authority, name or date counts.
 */
class RelayTrustManager(private val fingerprint: String) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The relay sent no certificate")
        if (leaf.keyFingerprint() != fingerprint) throw RelayKeyMismatchException()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkServerTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkServerTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Not a server")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Offers a server's TLS key as a client certificate too: to a relay, the server proves its identity
 * with the very key trackers pin. Key managers built for serving (the phone's Keystore one) only
 * answer server questions; their answers serve here.
 */
class ClientCertificateKeyManager(private val server: X509ExtendedKeyManager) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        keyType.orEmpty().firstNotNullOfOrNull { server.chooseServerAlias(it, null, socket) }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        keyType.orEmpty().firstNotNullOfOrNull { server.chooseEngineServerAlias(it, null, engine) }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = server.getServerAliases(keyType, issuers)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = server.getCertificateChain(alias)
    override fun getPrivateKey(alias: String?): PrivateKey? = server.getPrivateKey(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

/** TLS 1.3 and 1.2, as far as this platform has them: Android before 10 has no 1.3. */
fun SSLSocket.useModernProtocols() {
    val wanted = listOf("TLSv1.3", "TLSv1.2")
    enabledProtocols = wanted.filter { it in supportedProtocols }.toTypedArray()
}

fun clientTlsContext(keyManager: X509ExtendedKeyManager, relayFingerprint: String): SSLContext =
    SSLContext.getInstance("TLS").apply { init(arrayOf(ClientCertificateKeyManager(keyManager)), arrayOf(RelayTrustManager(relayFingerprint)), null) }
