package org.privatetracker.server.api.plugin

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.SealedBodies
import org.privatetracker.core.protocol.v1.SealedBodies.OpenResult
import org.privatetracker.server.api.ServerDependencies

/**
 * Opens the sealed [body] of a device route (see [SealedBodies]), or throws the problem the tracker
 * reacts to: an unsealed body comes from a tracker older than 0.5, an unknown key means it should
 * fetch the current one from health.
 */
internal suspend fun ApplicationCall.openSealed(deps: ServerDependencies, body: ByteArray): SealedBodies.OpenedRequest {
    val result = try {
        SealedBodies.open(body, request.httpMethod.value, request.path()) { id ->
            deps.encryptionKeys.decryptionKey(id)?.let { it.privateKey to it.key.publicKey }
        }
    } catch (e: DeviceKeyException) {
        application.log.error("Encryption key unavailable", e)
        throw ApiException(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR)
    }
    return when (result) {
        is OpenResult.Opened -> result.request
        OpenResult.NotSealed -> throw ApiException(
            HttpStatusCode.BadRequest,
            ErrorCode.ENCRYPTION_REQUIRED,
            "Seal the body for the server's encryption key, given by GET ${ApiV1.HEALTH}",
        )
        is OpenResult.UnknownKey -> throw ApiException(
            HttpStatusCode.Conflict,
            ErrorCode.ENCRYPTION_KEY_UNKNOWN,
            "This server no longer holds that key; fetch the current one from GET ${ApiV1.HEALTH}",
        )
        OpenResult.Malformed -> throw ApiException(HttpStatusCode.BadRequest, ErrorCode.MALFORMED_JSON, "The body is not a JSON object")
        OpenResult.Unreadable -> throw ApiException(HttpStatusCode.BadRequest, ErrorCode.DECRYPTION_FAILED, "The sealed body does not open")
    }
}

/** [plaintext] sealed for the tracker that sent [request]. */
internal fun sealedAnswer(request: SealedBodies.OpenedRequest, plaintext: String): ByteArray =
    SealedBodies.sealResponse(request, plaintext.encodeToByteArray())

internal suspend fun ApplicationCall.respondSealed(bytes: ByteArray, status: HttpStatusCode = HttpStatusCode.OK) {
    respondBytes(bytes, ContentType.Application.Json, status)
}
