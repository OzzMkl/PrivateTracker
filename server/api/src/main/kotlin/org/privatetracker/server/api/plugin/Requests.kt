package org.privatetracker.server.api.plugin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import kotlin.time.Duration.Companion.minutes

internal val DEVICE_RATE_LIMIT = RateLimitName("device")

/** Per remote address and device; registration has no device in its path, so it counts per address. */
internal fun Application.installRateLimiting(requestsPerMinute: Int) {
    install(RateLimit) {
        register(DEVICE_RATE_LIMIT) {
            rateLimiter(limit = requestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> rateLimitKey(call.request.local.remoteAddress, call.parameters[ApiV1.DEVICE_ID_PARAM]) }
        }
    }
}

/**
 * The limit is charged before the signature is checked, so it must not be keyed on the device alone:
 * anyone could then use up a real tracker's budget with unsigned requests from another address.
 */
internal fun rateLimitKey(remoteAddress: String, deviceIdParam: String?): String =
    remoteAddress + "|" + deviceIdParam?.lowercase().orEmpty()

/**
 * Reads a JSON body of at most [maxBytes] as raw bytes, which is what a request signature covers.
 * Checks the declared length first and then the bytes actually read, so a chunked request cannot
 * get around the limit.
 */
internal suspend fun ApplicationCall.receiveJsonBytes(maxBytes: Int = ApiV1.MAX_BODY_BYTES): ByteArray {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json")
    }
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > maxBytes) throw bodyTooLarge(maxBytes)

    val bytes = receiveChannel().readBuffer(maxBytes + 1L).readByteArray()
    if (bytes.size > maxBytes) throw bodyTooLarge(maxBytes)
    return bytes
}

/**
 * [sealed] bytes came out of an encrypted body: the parser's message may quote them, and problems
 * travel in the clear, so it is left out.
 */
internal fun <T> decodeJson(deserializer: DeserializationStrategy<T>, bytes: ByteArray, sealed: Boolean = false): T =
    try {
        ProtocolJson.decodeFromString(deserializer, bytes.decodeToString())
    } catch (e: SerializationException) {
        throw malformed(e, sealed)
    } catch (e: IllegalArgumentException) {
        throw malformed(e, sealed)
    }

private fun malformed(e: Exception, sealed: Boolean) = ApiException(
    HttpStatusCode.BadRequest,
    ErrorCode.MALFORMED_JSON,
    if (sealed) "The sealed body is not a valid request" else e.message?.lineSequence()?.firstOrNull(),
)

private fun bodyTooLarge(maxBytes: Int) =
    ApiException(HttpStatusCode.PayloadTooLarge, ErrorCode.BATCH_TOO_LARGE, "Body larger than $maxBytes bytes")

private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "localhost")

/** True when the request comes from the phone itself. Proxy headers are deliberately ignored. */
fun ApplicationCall.isLoopback(): Boolean = request.local.remoteAddress in LOOPBACK

internal fun ApplicationCall.remoteAddressOrNull(): String? = request.local.remoteAddress.takeIf { it.isNotBlank() }
