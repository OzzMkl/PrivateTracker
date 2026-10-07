package org.privatetracker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import org.privatetracker.core.domain.model.ServerPin
import java.io.Closeable
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts a server by the public key in its certificate and nothing else: no certificate authority,
 * no host name, no dates. Server certificates are self-signed and server addresses change, so the key
 * pinned at pairing is the server's identity. Pair it with a host name check that accepts any name.
 */
class PinnedKeyTrustManager(private val pin: ServerPin) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
        if (!pin.matches(Base64.getEncoder().encodeToString(leaf.publicKey.encoded))) throw ServerKeyMismatchException()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Trackers do not accept connections")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** What [PinnedKeyTrustManager] throws for a key it does not trust, to tell that apart from other TLS failures. */
class ServerKeyMismatchException : CertificateException("The server's key is not the one this tracker trusts")

/** A TLS context that trusts exactly the server [trustManager]'s pin names. */
fun pinnedSslContext(trustManager: PinnedKeyTrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

/** The HTTP client to reach a server trusted by [pin]: it talks to that server and to no other. */
fun interface PinnedClients {
    fun clientFor(pin: ServerPin): HttpClient
}

/**
 * One OkHttp client per pin. The few in use stay cached so connections get reused; one pushed out is
 * closed once its requests finish.
 */
class OkHttpPinnedClients(private val userAgent: String, private val maxClients: Int = 4) : PinnedClients, Closeable {
    private val clients = object : LinkedHashMap<ServerPin, HttpClient>(maxClients, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ServerPin, HttpClient>): Boolean {
            if (size <= maxClients) return false
            eldest.value.close()
            eldest.value.engine.close()
            return true
        }
    }

    @Synchronized
    override fun clientFor(pin: ServerPin): HttpClient = clients.getOrPut(pin) {
        val trustManager = PinnedKeyTrustManager(pin)
        val engine = OkHttp.create {
            config {
                // Ktor's engines otherwise share one pool, which closing any of them empties.
                connectionPool(ConnectionPool())
                sslSocketFactory(pinnedSslContext(trustManager).socketFactory, trustManager)
                // Certificates name no address, and the server's addresses change: its key is what counts.
                hostnameVerifier { _, _ -> true }
            }
        }
        createProtocolHttpClient(engine, userAgent)
    }

    @Synchronized
    override fun close() {
        clients.values.forEach {
            it.close()
            it.engine.close()
        }
        clients.clear()
    }
}
