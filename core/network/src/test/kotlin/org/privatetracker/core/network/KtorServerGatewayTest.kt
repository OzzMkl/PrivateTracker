package org.privatetracker.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.crypto.InMemoryEncryptionKeyVault
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.SealedBodies
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVER = "https://192.168.1.10:8787/"

/**
 * The mock engine has no TLS; which pin each call carries is checked through the client it asks for.
 * A fingerprint, so upload answers need no signed acknowledgment unless a test asks for one.
 */
private val PIN = ServerPin.Fingerprint("3F9A-01BC-77D2-E410")

class KtorServerGatewayTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val keys = InMemoryDeviceKeys()
    private val vault = InMemoryEncryptionKeyVault()
    private val generated = runBlocking { vault.generate() }
    private val encryptionKey = EncryptionKey(keyFingerprint(generated.publicKey)!!, generated.publicKey, T0.plusSeconds(3600))

    private val pins = mutableListOf<ServerPin>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorServerGateway {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = createProtocolHttpClient(engine, "PrivateTracker-Tracker/0.1.0")
        return KtorServerGateway({ pin -> client.also { pins += pin } }, keys, FakeClock())
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String, extra: Pair<String, String>? = null) =
        json(status, body.encodeToByteArray(), extra)

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: ByteArray, extra: Pair<String, String>? = null) =
        respond(
            content = body,
            status = status,
            headers = headersOf(
                *listOfNotNull(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()), extra?.let { it.first to listOf(it.second) })
                    .toTypedArray(),
            ),
        )

    /** What the server does: opens the request with the private key of [encryptionKey]. */
    private suspend fun open(request: HttpRequestData): SealedBodies.OpenedRequest {
        val privateKey = vault.privateKey(generated.protectedPrivateKey)
        val result = SealedBodies.open(request.body.toByteArray(), request.method.value, request.url.encodedPath) { id ->
            (privateKey to generated.publicKey).takeIf { id == encryptionKey.id }
        }
        return assertIs<SealedBodies.OpenResult.Opened>(result).request
    }

    /** The server's answer to a sealed request, sealed for the tracker that sent it. */
    private suspend fun MockRequestHandleScope.sealed(request: HttpRequestData, status: HttpStatusCode, body: String) =
        json(status, SealedBodies.sealResponse(open(request), body.encodeToByteArray()))

    @Test
    fun `registration posts the device sealed for the server's key, with our User-Agent`() = runTest {
        val gateway = gateway { request ->
            sealed(
                request,
                HttpStatusCode.Created,
                """{"device_id":"${DEVICE_A.value}","created":true,"max_batch_size":100,"server_time":"2026-10-02T18:00:00Z"}""",
            )
        }

        val result = gateway.register(SERVER, PIN, aRegistration(), encryptionKey).successValue()

        assertTrue(result.created)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://192.168.1.10:8787/api/v1/devices/register", request.url.toString())
        assertEquals("PrivateTracker-Tracker/0.1.0", request.headers[HttpHeaders.UserAgent])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        val wire = request.body.toByteArray().decodeToString()
        assertTrue(wire.startsWith("""{"key_id":"${encryptionKey.id}","enc":"""), wire)
        assertFalse("protocol_version" in wire || "Pixel" in wire, wire)
        assertTrue(open(request).plaintext.decodeToString().contains("\"protocol_version\":1"))
    }

    @Test
    fun `an upload returns the per-location result`() = runTest {
        val gateway = gateway { request ->
            sealed(
                request,
                HttpStatusCode.OK,
                """{"accepted":["${locationId(1).value}"],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}""",
            )
        }

        val result = gateway.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey).successValue()

        assertEquals(listOf(locationId(1)), result.accepted)
        assertEquals("/api/v1/devices/${DEVICE_A.value}/locations", requests.single().url.encodedPath)
    }

    @Test
    fun `device requests carry a signature over their method, path and exact body, health none`() = runTest {
        val gateway = gateway { request ->
            when {
                request.url.encodedPath.endsWith("/health") -> json(
                    HttpStatusCode.OK,
                    """{"status":"ok","server_name":"S","server_version":"0.2.0","protocol_version":1,"server_time":"2026-10-02T18:00:00Z"}""",
                )
                request.url.encodedPath.endsWith("/register") -> sealed(
                    request,
                    HttpStatusCode.Created,
                    """{"device_id":"${DEVICE_A.value}","created":true,"max_batch_size":100,"server_time":"2026-10-02T18:00:00Z","approval":"PENDING"}""",
                )
                else -> sealed(request, HttpStatusCode.OK, """{"accepted":[],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}""")
            }
        }

        gateway.health(SERVER, PIN).successValue()
        assertEquals(DeviceApproval.PENDING, gateway.register(SERVER, PIN, aRegistration(), encryptionKey).successValue().approval)
        gateway.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey).successValue()

        assertNull(requests[0].headers[HttpHeaders.Authorization])
        val publicKey = keys.publicKey(DEVICE_A)
        requests.drop(1).forEach { request ->
            val signature = assertNotNull(RequestSignature.parse(assertNotNull(request.headers[HttpHeaders.Authorization])))
            assertEquals(DEVICE_A.value, signature.deviceId)
            assertEquals(T0.epochSecond, signature.created)
            val input = RequestSignature.signingInput(
                request.method.value, request.url.encodedPath, signature.deviceId, signature.created, signature.nonce, request.body.toByteArray(),
            )
            assertTrue(EcdsaP256.verify(publicKey, input, signature.signature), request.url.encodedPath)
        }
        // The signature covers the sealed bytes; the key it registers travels inside them.
        assertFalse(requests[1].body.toByteArray().decodeToString().contains("\"public_key\""))
        assertTrue(open(requests[1]).plaintext.decodeToString().contains("\"public_key\""))
    }

    @Test
    fun `a redirect is never followed, so a health challenge cannot be passed on to another server`() = runTest {
        val gateway = gateway { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://192.168.1.11:8787/api/v1/health")) }

        assertEquals(DomainError.Http(302), gateway.health(SERVER, PIN, challenge = "Y2hhbGxlbmdlLTEyMzQ1Ng").failureError())
        assertEquals(1, requests.size)
    }

    @Test
    fun `with a whole key pinned, an upload answer counts only if the server signed it`() = runTest {
        val serverKeys = InMemoryServerKeys()
        val answer = """{"accepted":["${locationId(1).value}"],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}"""
        var signWith: InMemoryServerKeys? = serverKeys
        val gateway = gateway { request ->
            val nonce = RequestSignature.parse(request.headers[HttpHeaders.Authorization]!!)!!.nonce
            val sealedAnswer = SealedBodies.sealResponse(open(request), answer.encodeToByteArray())
            // Signed over the sealed bytes, as they travel.
            val ack = signWith?.sign(RequestSignature.ackInput(nonce, sealedAnswer))
            json(HttpStatusCode.OK, sealedAnswer, ack?.let { RequestSignature.ACK_HEADER to Base64.getEncoder().encodeToString(it) })
        }
        val pinned = ServerPin.Key(serverKeys.publicKey())
        suspend fun upload(pin: ServerPin) = gateway.uploadLocations(SERVER, pin, DEVICE_A, listOf(aLocation(1)), encryptionKey)

        assertEquals(listOf(locationId(1)), upload(pinned).successValue().accepted)
        signWith = InMemoryServerKeys()
        assertEquals(DomainError.ServerIdentityMismatch, upload(pinned).failureError())
        signWith = null
        assertEquals(DomainError.ServerIdentityMismatch, upload(pinned).failureError())
        // Trusted by a typed fingerprint only, there is no whole key to check against; TLS is the check.
        assertEquals(listOf(locationId(1)), upload(PIN).successValue().accepted)
    }

    @Test
    fun `every call goes through the client of its pin`() = runTest {
        val gateway = gateway { json(HttpStatusCode.NotFound, """{"type":"t","title":"t","status":404,"code":"DEVICE_NOT_REGISTERED"}""") }
        val other = ServerPin.Fingerprint("0000-1111-2222-3333")

        gateway.health(SERVER, PIN)
        gateway.register(SERVER, other, aRegistration(), encryptionKey)
        gateway.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey)

        assertEquals(listOf<ServerPin>(PIN, other, PIN), pins)
    }

    @Test
    fun `a server key the pin refuses is an identity mismatch, another failed handshake an invalid response`() = runTest {
        val refused = gateway { throw SSLHandshakeException("handshake failed").apply { initCause(ServerKeyMismatchException()) } }
        assertEquals(DomainError.ServerIdentityMismatch, refused.health(SERVER, PIN).failureError())

        val plainHttp = gateway { throw SSLHandshakeException("WRONG_VERSION_NUMBER") }
        assertIs<DomainError.Network.InvalidResponse>(plainHttp.health(SERVER, PIN).failureError())

        // A TLS connection dropped after the handshake is the network, retried like any other.
        val dropped = gateway { throw SSLException("Read error: ssl=0x7b, I/O error during system call, Connection reset by peer") }
        assertEquals(DomainError.Network.Unreachable, dropped.health(SERVER, PIN).failureError())
    }

    @Test
    fun `a key store failure is its own error and sends nothing`() = runTest {
        val broken = object : DeviceKeys {
            override suspend fun publicKey(deviceId: DeviceId): String = throw DeviceKeyException("no key")
            override suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray = throw DeviceKeyException("no key")
        }
        val engine = MockEngine { request ->
            requests += request
            respond("", HttpStatusCode.OK)
        }
        val client = createProtocolHttpClient(engine, "PrivateTracker-Tracker/0.1.0")
        val gateway = KtorServerGateway({ client }, broken, FakeClock())

        assertEquals(DomainError.DeviceKeyUnavailable, gateway.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation()), encryptionKey).failureError())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `problem responses become the matching domain errors`() = runTest {
        val notRegistered = gateway {
            json(HttpStatusCode.NotFound, """{"type":"t","title":"t","status":404,"code":"DEVICE_NOT_REGISTERED"}""")
        }
        assertEquals(DomainError.DeviceNotRegistered, notRegistered.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation()), encryptionKey).failureError())

        val limited = gateway {
            json(
                HttpStatusCode.TooManyRequests,
                """{"type":"t","title":"t","status":429,"code":"RATE_LIMITED"}""",
                HttpHeaders.RetryAfter to "30",
            )
        }
        assertEquals(DomainError.Http(429, "RATE_LIMITED", 30), limited.health(SERVER, PIN).failureError())

        val pending = gateway {
            json(HttpStatusCode.Forbidden, """{"type":"t","title":"t","status":403,"code":"DEVICE_PENDING_APPROVAL"}""")
        }
        assertEquals(DomainError.DevicePendingApproval, pending.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation()), encryptionKey).failureError())

        val expired = gateway {
            json(HttpStatusCode.Unauthorized, """{"type":"t","title":"t","status":401,"code":"SIGNATURE_EXPIRED"}""")
        }
        assertEquals(
            DomainError.AuthenticationFailed(AuthFailure.EXPIRED),
            expired.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation()), encryptionKey).failureError(),
        )
    }

    @Test
    fun `an answer that does not open with the request's own key is no answer`() = runTest {
        val answer = """{"accepted":["${locationId(1).value}"],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}"""
        var previous: SealedBodies.OpenedRequest? = null
        val replaying = gateway { request ->
            val opened = open(request)
            // Answers each request with what it sealed for the one before.
            json(HttpStatusCode.OK, SealedBodies.sealResponse(previous ?: opened, answer.encodeToByteArray())).also { previous = opened }
        }
        replaying.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey).successValue()
        assertIs<DomainError.Network.InvalidResponse>(replaying.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey).failureError())

        val plain = gateway { json(HttpStatusCode.OK, answer) }
        assertIs<DomainError.Network.InvalidResponse>(plain.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation(1)), encryptionKey).failureError())
    }

    @Test
    fun `a rotated key and a key that is not P-256 are reported as such`() = runTest {
        val rotated = gateway {
            json(HttpStatusCode.Conflict, """{"type":"t","title":"t","status":409,"code":"ENCRYPTION_KEY_UNKNOWN"}""")
        }
        assertEquals(DomainError.EncryptionKeyUnknown, rotated.uploadLocations(SERVER, PIN, DEVICE_A, listOf(aLocation()), encryptionKey).failureError())

        requests.clear()
        val broken = encryptionKey.copy(publicKey = "bm90IGEga2V5")
        assertEquals(DomainError.EncryptionUnavailable, rotated.register(SERVER, PIN, aRegistration(), broken).failureError())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an error page that is not a problem keeps only the status`() = runTest {
        val gateway = gateway { respond("<html>Bad Gateway</html>", HttpStatusCode.BadGateway) }

        assertEquals(DomainError.Http(502), gateway.health(SERVER, PIN).failureError())
    }

    @Test
    fun `an unreadable success body is an invalid response`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, """{"status":"ok"}""") }

        assertIs<DomainError.Network.InvalidResponse>(gateway.health(SERVER, PIN).failureError())
    }

    @Test
    fun `connection failures and timeouts are network errors`() = runTest {
        assertEquals(DomainError.Network.Unreachable, gateway { throw ConnectException("refused") }.health(SERVER, PIN).failureError())
        assertEquals(DomainError.Network.Timeout, gateway { throw SocketTimeoutException("slow") }.health(SERVER, PIN).failureError())
    }
}
