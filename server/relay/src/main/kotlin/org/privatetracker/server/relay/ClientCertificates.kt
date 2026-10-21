package org.privatetracker.server.relay

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Takes whatever certificate a server presents: they are self-signed, and what counts is their key,
 * which the relay compares with its list after the handshake. The handshake itself has made the
 * server prove it holds that key.
 */
internal object AnyClientCertificate : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("No certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("The relay connects to no one")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkServerTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkServerTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
