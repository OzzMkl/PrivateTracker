package org.privatetracker.server.api.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.getOrNull
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerIdentity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.mapper.toSummaryDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.dto.DevicesResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RejectedLocationDto
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.signedRequest
import org.privatetracker.server.api.plugin.ApiException
import org.privatetracker.server.api.plugin.decodeJson
import org.privatetracker.server.api.plugin.describe
import org.privatetracker.server.api.plugin.getOrThrowApi
import org.privatetracker.server.api.plugin.receiveJsonBytes
import org.privatetracker.server.api.plugin.remoteAddressOrNull
import org.privatetracker.server.api.plugin.toApiException
import java.util.Base64

fun Route.healthRoutes(deps: ServerDependencies) {
    get(ApiV1.HEALTH) {
        val config = deps.serverConfig.get()
        val now = deps.clock.now()
        val challenge = call.request.queryParameters[ApiV1.CHALLENGE_PARAM]?.takeIf(CHALLENGE::matches)
        // Without a working key the server still answers; a paired tracker then refuses it, as it should.
        val identity = challenge?.let {
            try {
                ServerIdentity(deps.serverKeys.publicKey(), Base64.getEncoder().encodeToString(deps.serverKeys.sign(serverIdentityInput(it, now))))
            } catch (e: DeviceKeyException) {
                call.application.log.error("Server key unavailable", e)
                null
            }
        }
        call.respond(ServerInfo(config.serverName, deps.serverVersion, ApiV1.PROTOCOL_VERSION, now, identity).toDto())
    }
}

private val CHALLENGE = Regex("^[A-Za-z0-9_-]{16,64}$")

fun Route.deviceRoutes(deps: ServerDependencies) {
    // Signed with the key it carries: the tracker proves it holds the key it registers.
    post(ApiV1.REGISTER) {
        val body = call.receiveJsonBytes()
        val signed = call.signedRequest(body)
        val registration = decodeJson(RegisterDeviceRequest.serializer(), body).toDomain().getOrThrowApi()
        val result = deps.registerOrUpdateDevice(registration, signed, call.remoteAddressOrNull()).getOrThrowApi()
        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.toDto())
    }

    get(ApiV1.DEVICES) {
        call.requireReadAccess(deps)
        call.respond(DevicesResponse(deps.getDeviceOverviews().map { it.toSummaryDto() }))
    }

    get(ApiV1.DEVICE) {
        call.requireReadAccess(deps)
        val id = DeviceId.parse(call.parameters[ApiV1.DEVICE_ID_PARAM].orEmpty())
            ?: throw DomainError.DeviceNotFound.toApiException()
        call.respond(deps.getDeviceDetail(id).getOrThrowApi().toDto())
    }
}

fun Route.locationRoutes(deps: ServerDependencies) {
    post(ApiV1.LOCATIONS) {
        val deviceId = DeviceId.parse(call.parameters[ApiV1.DEVICE_ID_PARAM].orEmpty())
            ?: throw ApiException(HttpStatusCode.UnprocessableEntity, ErrorCode.VALIDATION_FAILED, "device_id: invalid_format")
        val body = call.receiveJsonBytes()
        val signed = call.signedRequest(body)
        // A device signs for itself only; its signature on another device's path proves nothing.
        if (signed.deviceId != deviceId) throw DomainError.AuthenticationFailed(AuthFailure.INVALID).toApiException()
        deps.authenticateDevice(signed).getOrThrowApi()
        val request = decodeJson(LocationBatchRequest.serializer(), body)
        if (request.locations.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, ErrorCode.EMPTY_BATCH, "locations must not be empty")
        }
        val maxBatchSize = deps.serverConfig.get().maxBatchSize
        if (request.locations.size > maxBatchSize) throw DomainError.BatchTooLarge(maxBatchSize).toApiException()

        // A malformed id or timestamp rejects that one location, not the whole batch.
        val mapped = request.locations.map { dto -> dto to dto.toDomain(deviceId) }
        val malformed = mapped.mapNotNull { (dto, outcome) ->
            val error = (outcome as? Outcome.Failure)?.error as? DomainError.Validation ?: return@mapNotNull null
            RejectedLocationDto(dto.id, RejectionReason.INVALID_FIELD.name, error.describe())
        }
        val valid = mapped.mapNotNull { (_, outcome) -> outcome.getOrNull() }

        val result = deps.ingestLocationBatch(deviceId, valid, call.remoteAddressOrNull()).getOrThrowApi().toDto()
        val response = ProtocolJson.encodeToString(LocationBatchResponse.serializer(), result.copy(rejected = malformed + result.rejected))
        // Signed acknowledgment: a paired tracker deletes nothing on an answer this server did not sign.
        val bytes = response.encodeToByteArray()
        try {
            val ack = deps.serverKeys.sign(RequestSignature.ackInput(signed.nonce, bytes))
            call.response.header(RequestSignature.ACK_HEADER, Base64.getEncoder().encodeToString(ack))
        } catch (e: DeviceKeyException) {
            call.application.log.error("Server key unavailable; the acknowledgment goes unsigned", e)
        }
        call.respondBytes(bytes, ContentType.Application.Json)
    }
}

private suspend fun ApplicationCall.requireReadAccess(deps: ServerDependencies) {
    if (!deps.serverConfig.get().exposeReadApi && !deps.isLocalRequest(this)) {
        throw ApiException(HttpStatusCode.Forbidden, ErrorCode.LOCAL_ONLY, "Read endpoints only answer requests from the server itself")
    }
}
