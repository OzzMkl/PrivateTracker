package org.privatetracker.core.protocol.mapper

import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.getOrNull
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceDetail
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.MAX_ADVERTISED_ADDRESSES
import org.privatetracker.core.domain.model.PairingClaim
import org.privatetracker.core.domain.model.PeerJoin
import org.privatetracker.core.domain.model.PeerWelcome
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.RejectedLocation
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.RemovedDevice
import org.privatetracker.core.domain.model.ServerIdentity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.SignedEncryptionKey
import org.privatetracker.core.domain.model.SyncBatch
import org.privatetracker.core.domain.model.SyncCursor
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.validation.validateDeviceName
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.dto.DeviceDetailDto
import org.privatetracker.core.protocol.v1.dto.DeviceSummaryDto
import org.privatetracker.core.protocol.v1.dto.EncryptionKeyDto
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.JoinPeerRequest
import org.privatetracker.core.protocol.v1.dto.JoinPeerResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.PairingClaimDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import org.privatetracker.core.protocol.v1.dto.RejectedLocationDto
import org.privatetracker.core.protocol.v1.dto.RemovedDeviceDto
import org.privatetracker.core.protocol.v1.dto.SessionDto
import org.privatetracker.core.protocol.v1.dto.SyncCursorDto
import org.privatetracker.core.protocol.v1.dto.SyncDeviceDto
import org.privatetracker.core.protocol.v1.dto.SyncLocationDto
import org.privatetracker.core.protocol.v1.dto.SyncMessageDto
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
    publicKey = publicKey,
    pairing = pairing?.let { PairingClaimDto(it.ticketId, it.proof) },
)

fun RegisterDeviceRequest.toDomain(): Outcome<DeviceRegistration> {
    val id = DeviceId.parse(deviceId) ?: return invalid("device_id")
    val key = publicKey ?: return DomainError.Validation("public_key", FieldViolation.REQUIRED).asFailure()
    val claim = pairing?.let { PairingClaim(it.ticket, it.proof) }
    return DeviceRegistration(id, name, Platform.parse(platform), appVersion, protocolVersion, key, claim).asSuccess()
}

fun RegistrationResult.toDto(): RegisterDeviceResponse =
    RegisterDeviceResponse(deviceId.value, created, maxBatchSize, WireTime.format(serverTime), approval.name)

fun RegisterDeviceResponse.toDomain(): Outcome<RegistrationResult> {
    val id = DeviceId.parse(deviceId) ?: return invalid("device_id")
    val time = WireTime.parse(serverTime) ?: return invalid("server_time")
    val state = DeviceApproval.entries.firstOrNull { it.name == approval } ?: return invalid("approval")
    return RegistrationResult(id, created, maxBatchSize, time, state).asSuccess()
}

// Health

fun ServerInfo.toDto(): HealthResponse {
    val signed = identity?.takeIf { it.addressesSignature != null }
    return HealthResponse(
        "ok", name, version, protocolVersion, WireTime.format(serverTime), identity?.publicKey, identity?.signature, encryptionKey?.toDto(),
        signed?.addresses, signed?.addressesSignature,
    )
}

fun HealthResponse.toDomain(): Outcome<ServerInfo> {
    val time = WireTime.parse(serverTime) ?: return invalid("server_time")
    val identity = if (serverKey != null && signature != null) {
        ServerIdentity(serverKey, signature, addresses.orEmpty(), addressesSignature)
    } else {
        null
    }
    val encryption = encryptionKey?.let { dto ->
        val useUntil = WireTime.parse(dto.useUntil) ?: return invalid("encryption_key.use_until")
        SignedEncryptionKey(EncryptionKey(dto.id, dto.publicKey, useUntil), dto.signature)
    }
    return ServerInfo(serverName, serverVersion, protocolVersion, time, identity, encryption).asSuccess()
}

fun SignedEncryptionKey.toDto(): EncryptionKeyDto = EncryptionKeyDto(key.id, key.publicKey, WireTime.format(key.useUntil), signature)

// Read API

fun DeviceOverview.toSummaryDto(): DeviceSummaryDto = DeviceSummaryDto(
    deviceId = device.id.value,
    name = device.name,
    status = status.name,
    approval = device.approval.name,
    lastSeenAt = device.lastSeenAt?.let(WireTime::format),
    lastLocation = lastLocation?.toDto(),
)

fun DeviceDetail.toDto(): DeviceDetailDto = DeviceDetailDto(
    deviceId = overview.device.id.value,
    name = overview.device.name,
    platform = overview.device.platform.name,
    status = overview.status.name,
    approval = overview.device.approval.name,
    keyFingerprint = overview.device.publicKey?.let(::keyFingerprint),
    createdAt = WireTime.format(overview.device.createdAt),
    appVersion = overview.device.appVersion,
    lastSeenAt = overview.device.lastSeenAt?.let(WireTime::format),
    lastLocation = overview.lastLocation?.toDto(),
    currentSession = currentSession?.toDto(),
)

fun DeviceSession.toDto(): SessionDto =
    SessionDto(WireTime.format(startedAt), WireTime.format(lastActivityAt), locationsReceived)

// Paired servers, from 1.0 on

fun PeerJoin.toDto(): JoinPeerRequest = JoinPeerRequest(ticketId, serverName, publicKey, addresses, proof)

fun JoinPeerRequest.toDomain(): PeerJoin = PeerJoin(ticket, name, publicKey, addresses.take(MAX_ADVERTISED_ADDRESSES * 2), proof)

fun PeerWelcome.toDto(): JoinPeerResponse = JoinPeerResponse(serverName, addresses)

fun JoinPeerResponse.toDomain(): PeerWelcome = PeerWelcome(name, addresses.take(MAX_ADVERTISED_ADDRESSES * 2))

fun SyncBatch.toDto(): SyncMessageDto = SyncMessageDto(
    serverName = serverName,
    addresses = addresses,
    have = have.toDto(),
    upTo = upTo.toDto(),
    devices = devices.map { it.toSyncDto() },
    removedDevices = removedDevices.map { RemovedDeviceDto(it.id.value, WireTime.format(it.removedAt)) },
    locations = locations.map { it.toSyncDto() },
    more = more,
    reset = reset,
)

private fun SyncCursor.toDto() = SyncCursorDto(locations, devices)

private fun SyncCursorDto.toDomain() = SyncCursor(locations, devices)

private fun Device.toSyncDto() = SyncDeviceDto(
    deviceId = id.value,
    name = name,
    platform = platform.name,
    appVersion = appVersion,
    protocolVersion = protocolVersion,
    publicKey = publicKey,
    approval = approval.name,
    createdAt = WireTime.format(createdAt),
    updatedAt = updatedAt?.let(WireTime::format),
    approvalChangedAt = approvalChangedAt?.let(WireTime::format),
    lastSeenAt = lastSeenAt?.let(WireTime::format),
)

private fun Location.toSyncDto() = SyncLocationDto(
    deviceId = deviceId.value,
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
    receivedAt = WireTime.format(checkNotNull(receivedAt) { "A stored location has its arrival time" }),
)

/**
 * The batch a paired server sent. What does not parse is left out rather than failing the batch: a
 * newer server may send what this one cannot read yet, and the cursors still move on. Fails only
 * when the batch is larger than any sender may make it.
 */
fun SyncMessageDto.toDomain(): Outcome<SyncBatch> {
    if (locations.size > ApiV1.MAX_SYNC_LOCATIONS || devices.size > ApiV1.MAX_SYNC_DEVICES || removedDevices.size > ApiV1.MAX_SYNC_DEVICES) {
        return DomainError.Validation("batch", FieldViolation.TOO_LONG).asFailure()
    }
    return SyncBatch(
        serverName = serverName,
        addresses = addresses.take(MAX_ADVERTISED_ADDRESSES * 2),
        have = have.toDomain(),
        upTo = upTo.toDomain(),
        devices = devices.mapNotNull { it.toDomain() },
        removedDevices = removedDevices.mapNotNull { dto ->
            val id = DeviceId.parse(dto.deviceId) ?: return@mapNotNull null
            WireTime.parse(dto.removedAt)?.let { RemovedDevice(id, it) }
        },
        locations = locations.mapNotNull { it.toDomain() },
        more = more,
        reset = reset,
    ).asSuccess()
}

private fun SyncDeviceDto.toDomain(): Device? {
    val id = DeviceId.parse(deviceId) ?: return null
    val created = WireTime.parse(createdAt) ?: return null
    if (validateDeviceName("name", name) != null) return null
    return Device(
        id = id,
        name = name.trim(),
        platform = Platform.parse(platform),
        appVersion = appVersion?.take(APP_VERSION_MAX_LENGTH),
        protocolVersion = protocolVersion,
        createdAt = created,
        lastSeenAt = lastSeenAt?.let(WireTime::parse),
        publicKey = publicKey,
        // An approval this version does not know refuses the device, as storage does.
        approval = DeviceApproval.entries.firstOrNull { it.name == approval } ?: DeviceApproval.REJECTED,
        updatedAt = updatedAt?.let(WireTime::parse),
        approvalChangedAt = approvalChangedAt?.let(WireTime::parse),
    )
}

private fun SyncLocationDto.toDomain(): Location? {
    val device = DeviceId.parse(deviceId) ?: return null
    val received = WireTime.parse(receivedAt) ?: return null
    val location = LocationDto(id, latitude, longitude, accuracyM, altitudeM, speedMps, bearingDeg, provider, batteryPct, isMock, recordedAt)
        .toDomain(device).getOrNull() ?: return null
    return location.copy(receivedAt = received)
}

private const val APP_VERSION_MAX_LENGTH = 64

// Errors

/** Client side: turns a problem response into the domain error the use cases react to. */
fun ProblemDetails?.toDomainError(httpStatus: Int, retryAfterSeconds: Long?): DomainError =
    when (ErrorCode.parse(this?.code)) {
        ErrorCode.DEVICE_NOT_REGISTERED -> DomainError.DeviceNotRegistered
        ErrorCode.DEVICE_NOT_FOUND -> DomainError.DeviceNotFound
        ErrorCode.NEW_DEVICES_DISABLED -> DomainError.NewDevicesDisabled
        ErrorCode.BATCH_TOO_LARGE -> DomainError.BatchTooLarge(this?.maxBatchSize ?: 1)
        ErrorCode.DEVICE_PENDING_APPROVAL -> DomainError.DevicePendingApproval
        ErrorCode.DEVICE_REJECTED -> DomainError.DeviceRejected
        ErrorCode.PAIRING_INVALID -> DomainError.PairingInvalid
        ErrorCode.PEER_UNKNOWN -> DomainError.PeerUnknown
        ErrorCode.ENCRYPTION_KEY_UNKNOWN -> DomainError.EncryptionKeyUnknown
        else -> AuthFailure.entries.firstOrNull { it.code == this?.code }?.let(DomainError::AuthenticationFailed)
            ?: DomainError.Http(httpStatus, this?.code, retryAfterSeconds)
    }
