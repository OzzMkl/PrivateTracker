package org.privatetracker.feature.server.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.network.OkHttpPinnedClients
import org.privatetracker.core.network.PinnedKeyTrustManager
import org.privatetracker.core.network.pinnedSslContext
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.security.KeystoreKeys
import org.privatetracker.core.security.KeystoreServerKeys
import java.io.IOException
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * The 0.4 exit criterion on a real Android TLS stack: the server answers only over TLS, with its
 * Keystore key, and a tracker accepts it only when the key matches its pin.
 */
@RunWith(AndroidJUnit4::class)
class TlsServerTest {
    private val port = 41_000 + (System.nanoTime() % 1000).toInt()
    private val serverKeys = KeystoreServerKeys(KeystoreKeys())
    private val server = runBlocking {
        tlsServer(port, "127.0.0.1", serverKeys.tlsKeyManager()) {
            routing {
                get("/ping") { call.respondText("pong") }
                get(ApiV1.HEALTH) { call.respondText(HEALTH, ContentType.Application.Json) }
            }
        }.start(wait = false)
    }

    @After
    fun tearDown() = server.stop(0, 1_000)

    private fun get(pin: ServerPin): String {
        val connection = URL("https://127.0.0.1:$port/ping").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = pinnedSslContext(PinnedKeyTrustManager(pin)).socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    @Test
    fun aTrackerPinnedToTheServerKeyGetsThrough_byKeyAndByTypedFingerprint() = runBlocking {
        val key = serverKeys.publicKey()

        assertEquals("pong", get(ServerPin.Key(key)))
        assertEquals("pong", get(ServerPin.Fingerprint(keyFingerprint(key)!!)))
    }

    @Test
    fun aServerWithAnotherKeyIsRefused() {
        val other = EcdsaP256.encode(EcdsaP256.generateKeyPair().public)
        try {
            get(ServerPin.Key(other))
            fail("A server with another key must be refused")
        } catch (e: SSLException) {
            // Expected: the handshake fails before any request is sent.
        }
    }

    /** The app's own client, OkHttp with a pinned trust manager, and its error mapping on Conscrypt. */
    @Test
    fun theTrackerClientGetsThroughWithThePinAndReportsAnotherKeyAsAnIdentityMismatch() = runBlocking {
        val key = serverKeys.publicKey()
        val clients = OkHttpPinnedClients("PrivateTracker-Test")
        val gateway = KtorServerGateway(clients, InMemoryDeviceKeys())
        val url = "https://127.0.0.1:$port"
        try {
            assertEquals("S", gateway.health(url, ServerPin.Key(key)).successValue().name)
            assertEquals("S", gateway.health(url, ServerPin.Fingerprint(keyFingerprint(key)!!)).successValue().name)
            val other = ServerPin.Key(EcdsaP256.encode(EcdsaP256.generateKeyPair().public))
            assertEquals(DomainError.ServerIdentityMismatch, (gateway.health(url, other) as Outcome.Failure).error)
        } finally {
            clients.close()
        }
    }

    /** A raw socket: HttpURLConnection could refuse cleartext itself and pass this without asking the server. */
    @Test
    fun nothingIsServedInTheClear() {
        val answer = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".encodeToByteArray())
                flush()
            }
            try {
                socket.getInputStream().readBytes().decodeToString()
            } catch (e: IOException) {
                ""
            }
        }
        assertTrue("A plain HTTP request must not get an answer, got: $answer", !answer.startsWith("HTTP/"))
    }
}

private const val HEALTH =
    """{"status":"ok","server_name":"S","server_version":"0.4.0","protocol_version":1,"server_time":"2026-10-03T18:00:00Z"}"""

private fun <T> Outcome<T>.successValue(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AssertionError("Expected success but got ${error.code}")
}
