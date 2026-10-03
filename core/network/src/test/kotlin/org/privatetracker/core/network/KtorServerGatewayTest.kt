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
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
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
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.v1.RequestSignature
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVER = "http://192.168.1.10:8787/"

class KtorServerGatewayTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val keys = InMemoryDeviceKeys()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorServerGateway {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return KtorServerGateway(createProtocolHttpClient(engine, "PrivateTracker-Tracker/0.1.0"), keys, FakeClock())
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String, extra: Pair<String, String>? = null) =
        respond(
            content = body,
            status = status,
            headers = headersOf(
                *listOfNotNull(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()), extra?.let { it.first to listOf(it.second) })
                    .toTypedArray(),
            ),
        )

    @Test
    fun `registration posts the device as JSON with our User-Agent`() = runTest {
        val gateway = gateway {
            json(
                HttpStatusCode.Created,
                """{"device_id":"${DEVICE_A.value}","created":true,"max_batch_size":100,"server_time":"2026-10-02T18:00:00Z"}""",
            )
        }

        val result = gateway.register(SERVER, aRegistration()).successValue()

        assertTrue(result.created)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://192.168.1.10:8787/api/v1/devices/register", request.url.toString())
        assertEquals("PrivateTracker-Tracker/0.1.0", request.headers[HttpHeaders.UserAgent])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertTrue(request.body.toByteArray().decodeToString().contains("\"protocol_version\":1"))
    }

    @Test
    fun `an upload returns the per-location result`() = runTest {
        val gateway = gateway {
            json(
                HttpStatusCode.OK,
                """{"accepted":["${locationId(1).value}"],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}""",
            )
        }

        val result = gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1))).successValue()

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
                request.url.encodedPath.endsWith("/register") -> json(
                    HttpStatusCode.Created,
                    """{"device_id":"${DEVICE_A.value}","created":true,"max_batch_size":100,"server_time":"2026-10-02T18:00:00Z","approval":"PENDING"}""",
                )
                else -> json(HttpStatusCode.OK, """{"accepted":[],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}""")
            }
        }

        gateway.health(SERVER).successValue()
        assertEquals(DeviceApproval.PENDING, gateway.register(SERVER, aRegistration()).successValue().approval)
        gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1))).successValue()

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
        assertTrue(requests[1].body.toByteArray().decodeToString().contains("\"public_key\""))
    }

    @Test
    fun `a redirect is never followed, so a health challenge cannot be passed on to another server`() = runTest {
        val gateway = gateway { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://192.168.1.11:8787/api/v1/health")) }

        assertEquals(DomainError.Http(302), gateway.health(SERVER, challenge = "Y2hhbGxlbmdlLTEyMzQ1Ng").failureError())
        assertEquals(1, requests.size)
    }

    @Test
    fun `with a pinned key, an upload answer counts only if the server signed it`() = runTest {
        val serverKeys = InMemoryServerKeys()
        val answer = """{"accepted":["${locationId(1).value}"],"duplicates":[],"rejected":[],"server_time":"2026-10-02T18:00:00Z"}"""
        var signWith: InMemoryServerKeys? = serverKeys
        val gateway = gateway { request ->
            val nonce = RequestSignature.parse(request.headers[HttpHeaders.Authorization]!!)!!.nonce
            val ack = signWith?.sign(RequestSignature.ackInput(nonce, answer.encodeToByteArray()))
            json(HttpStatusCode.OK, answer, ack?.let { RequestSignature.ACK_HEADER to Base64.getEncoder().encodeToString(it) })
        }
        val pinned = serverKeys.publicKey()

        assertEquals(listOf(locationId(1)), gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1)), pinned).successValue().accepted)
        signWith = InMemoryServerKeys()
        assertEquals(DomainError.ServerIdentityMismatch, gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1)), pinned).failureError())
        signWith = null
        assertEquals(DomainError.ServerIdentityMismatch, gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1)), pinned).failureError())
        // A server typed in by hand has no key to check against.
        assertEquals(listOf(locationId(1)), gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation(1))).successValue().accepted)
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
        val gateway = KtorServerGateway(createProtocolHttpClient(engine, "PrivateTracker-Tracker/0.1.0"), broken, FakeClock())

        assertEquals(DomainError.DeviceKeyUnavailable, gateway.uploadLocations(SERVER, DEVICE_A, listOf(aLocation())).failureError())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `problem responses become the matching domain errors`() = runTest {
        val notRegistered = gateway {
            json(HttpStatusCode.NotFound, """{"type":"t","title":"t","status":404,"code":"DEVICE_NOT_REGISTERED"}""")
        }
        assertEquals(DomainError.DeviceNotRegistered, notRegistered.uploadLocations(SERVER, DEVICE_A, listOf(aLocation())).failureError())

        val limited = gateway {
            json(
                HttpStatusCode.TooManyRequests,
                """{"type":"t","title":"t","status":429,"code":"RATE_LIMITED"}""",
                HttpHeaders.RetryAfter to "30",
            )
        }
        assertEquals(DomainError.Http(429, "RATE_LIMITED", 30), limited.health(SERVER).failureError())

        val pending = gateway {
            json(HttpStatusCode.Forbidden, """{"type":"t","title":"t","status":403,"code":"DEVICE_PENDING_APPROVAL"}""")
        }
        assertEquals(DomainError.DevicePendingApproval, pending.uploadLocations(SERVER, DEVICE_A, listOf(aLocation())).failureError())

        val expired = gateway {
            json(HttpStatusCode.Unauthorized, """{"type":"t","title":"t","status":401,"code":"SIGNATURE_EXPIRED"}""")
        }
        assertEquals(
            DomainError.AuthenticationFailed(AuthFailure.EXPIRED),
            expired.uploadLocations(SERVER, DEVICE_A, listOf(aLocation())).failureError(),
        )
    }

    @Test
    fun `an error page that is not a problem keeps only the status`() = runTest {
        val gateway = gateway { respond("<html>Bad Gateway</html>", HttpStatusCode.BadGateway) }

        assertEquals(DomainError.Http(502), gateway.health(SERVER).failureError())
    }

    @Test
    fun `an unreadable success body is an invalid response`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, """{"status":"ok"}""") }

        assertIs<DomainError.Network.InvalidResponse>(gateway.health(SERVER).failureError())
    }

    @Test
    fun `connection failures and timeouts are network errors`() = runTest {
        assertEquals(DomainError.Network.Unreachable, gateway { throw ConnectException("refused") }.health(SERVER).failureError())
        assertEquals(DomainError.Network.Timeout, gateway { throw SocketTimeoutException("slow") }.health(SERVER).failureError())
    }
}
