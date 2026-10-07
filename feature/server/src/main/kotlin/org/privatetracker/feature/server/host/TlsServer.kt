package org.privatetracker.feature.server.host

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager

private val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

/**
 * [module] on Netty, over TLS only: from 0.4 nothing travels in the clear. Ktor's own TLS connector
 * copies the private key into a temporary key store, which a Keystore key cannot leave, so the TLS
 * handler goes in front of a plain connector instead, with the key manager signing inside the Keystore.
 * HTTP/2 stays off: the protocol needs HTTP/1.1 only, and ALPN on Android's TLS stack is not worth the risk.
 */
fun tlsServer(
    port: Int,
    host: String,
    keyManager: X509ExtendedKeyManager,
    module: Application.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    // Android before 10 has no TLS 1.3 and refuses a list that names it, failing every connection.
    val supported = SSLContext.getDefault().supportedSSLParameters.protocols.toSet()
    val tls = SslContextBuilder.forServer(keyManager)
        .sslProvider(SslProvider.JDK)
        .protocols(PROTOCOLS.filter { it in supported })
        .build()
    return embeddedServer(
        Netty,
        applicationEnvironment(),
        configure = {
            connector {
                this.port = port
                this.host = host
            }
            enableHttp2 = false
            channelPipelineConfig = { addFirst("tls", tls.newHandler(channel().alloc())) }
        },
        module = module,
    )
}
