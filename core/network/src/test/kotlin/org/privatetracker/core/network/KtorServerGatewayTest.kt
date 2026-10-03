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
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SERVER = "http://192.168.1.10:8787/"

class KtorServerGatewayTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorServerGateway {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return KtorServerGateway(createProtocolHttpClient(engine, "PrivateTracker-Tracker/0.1.0"))
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
