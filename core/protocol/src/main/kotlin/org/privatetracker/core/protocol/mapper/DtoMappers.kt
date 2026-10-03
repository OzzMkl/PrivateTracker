package org.privatetracker.core.protocol.mapper

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.domain.model.DeviceDetail
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.RejectedLocation
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.dto.DeviceDetailDto
import org.privatetracker.core.protocol.v1.dto.DeviceSummaryDto
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import org.privatetracker.core.protocol.v1.dto.RejectedLocationDto
import org.privatetracker.core.protocol.v1.dto.SessionDto
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** ISO 8601 timestamps on the wire. Writes UTC with `Z`; reads any offset. */
object WireTime {
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
    fun parse(raw: String): Instant? = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}

private fun invalid(vararg fields: String): Outcome<Nothing> =
    DomainError.Validation(fields.map { FieldViolation(it, FieldViolation.INVALID_FORMAT) }).asFailure()

// Locations

fun Location.toDto(): LocationDto = LocationDto(
    id = id.value,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    provider = provider,
    batteryPct = batteryPct,
    isMock = isMock,
    recordedAt = WireTime.format(recordedAt),
)

/** The device comes from the request path; a payload can never claim to be another device. */
fun LocationDto.toDomain(deviceId: DeviceId): Outcome<Location> {
    val locationId = LocationId.parse(id)
    val recorded = WireTime.parse(recordedAt)
    if (locationId == null || recorded == null) {
        return invalid(*listOfNotNull("id".takeIf { locationId == null }, "recorded_at".takeIf { recorded == null }).toTypedArray())
    }
    return Location(
        id = locationId,
        deviceId = deviceId,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        provider = provider,
        batteryPct = batteryPct,
        isMock = isMock,
        recordedAt = recorded,
    ).asSuccess()
}

fun LocationBatchResult.toDto(): LocationBatchResponse = LocationBatchResponse(
    accepted = accepted.map { it.value },
    duplicates = duplicates.map { it.value },
    rejected = rejected.map { it.toDto() },
    serverTime = WireTime.format(serverTime),
)

fun RejectedLocation.toDto(): RejectedLocationDto = RejectedLocationDto(id, reason.name, detail)

fun LocationBatchResponse.toDomain(): Outcome<LocationBatchResult> {
    val time = WireTime.parse(serverTime) ?: return invalid("server_time")
    val acceptedIds = accepted.map { LocationId.parse(it) ?: return invalid("accepted") }
    val duplicateIds = duplicates.map { LocationId.parse(it) ?: return invalid("duplicates") }
    val rejections = rejected.map {
        val reason = RejectionReason.entries.firstOrNull { reason -> reason.name == it.code } ?: RejectionReason.INVALID_FIELD
        RejectedLocation(it.id, reason, it.detail)
    }
    return LocationBatchResult(acceptedIds, duplicateIds, rejections, time).asSuccess()
}

// Registration

fun DeviceRegistration.toDto(): RegisterDeviceRequest = RegisterDeviceRequest(
    deviceId = deviceId.value,
    name = name,
    platform = platform.name,
    appVersion = appVersion,
    protocolVersion = protocolVersion,
)

fun RegisterDeviceRequest.toDomain(): Outcome<DeviceRegistration> {
    val id = DeviceId.parse(deviceId) ?: return invalid("device_id")
    return DeviceRegistration(id, name, Platform.parse(platform), appVersion, protocolVersion).asSuccess()
}

fun RegistrationResult.toDto(): RegisterDeviceResponse =
    RegisterDeviceResponse(deviceId.value, created, maxBatchSize, WireTime.format(serverTime))

fun RegisterDeviceResponse.toDomain(): Outcome<RegistrationResult> {
    val id = DeviceId.parse(deviceId) ?: return invalid("device_id")
    val time = WireTime.parse(serverTime) ?: return invalid("server_time")
    return RegistrationResult(id, created, maxBatchSize, time).asSuccess()
}

// Health

fun ServerInfo.toDto(): HealthResponse =
    HealthResponse("ok", name, version, protocolVersion, WireTime.format(serverTime))

fun HealthResponse.toDomain(): Outcome<ServerInfo> {
    val time = WireTime.parse(serverTime) ?: return invalid("server_time")
    return ServerInfo(serverName, serverVersion, protocolVersion, time).asSuccess()
}

// Read API

fun DeviceOverview.toSummaryDto(): DeviceSummaryDto = DeviceSummaryDto(
    deviceId = device.id.value,
    name = device.name,
    status = status.name,
    lastSeenAt = device.lastSeenAt?.let(WireTime::format),
    lastLocation = lastLocation?.toDto(),
)

fun DeviceDetail.toDto(): DeviceDetailDto = DeviceDetailDto(
    deviceId = overview.device.id.value,
    name = overview.device.name,
    platform = overview.device.platform.name,
    status = overview.status.name,
    createdAt = WireTime.format(overview.device.createdAt),
    appVersion = overview.device.appVersion,
    lastSeenAt = overview.device.lastSeenAt?.let(WireTime::format),
    lastLocation = overview.lastLocation?.toDto(),
    currentSession = currentSession?.toDto(),
)

fun DeviceSession.toDto(): SessionDto =
    SessionDto(WireTime.format(startedAt), WireTime.format(lastActivityAt), locationsReceived)

// Errors

/** Client side: turns a problem response into the domain error the use cases react to. */
fun ProblemDetails?.toDomainError(httpStatus: Int, retryAfterSeconds: Long?): DomainError =
    when (ErrorCode.parse(this?.code)) {
        ErrorCode.DEVICE_NOT_REGISTERED -> DomainError.DeviceNotRegistered
        ErrorCode.DEVICE_NOT_FOUND -> DomainError.DeviceNotFound
        ErrorCode.NEW_DEVICES_DISABLED -> DomainError.NewDevicesDisabled
        ErrorCode.BATCH_TOO_LARGE -> DomainError.BatchTooLarge(this?.maxBatchSize ?: 1)
        else -> DomainError.Http(httpStatus, this?.code, retryAfterSeconds)
    }
