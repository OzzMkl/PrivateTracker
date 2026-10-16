package org.privatetracker.core.data.mapper

import org.privatetracker.core.database.server.DeviceEntity
import org.privatetracker.core.database.server.DeviceWithLastLocationRow
import org.privatetracker.core.database.server.LocationEntity
import org.privatetracker.core.database.server.SessionRow
import org.privatetracker.core.database.tracker.OutboxLocationEntity
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.model.SessionId
import java.time.Instant

// Storage keeps epoch milliseconds; sub-millisecond precision is dropped on purpose.

internal fun Instant.millis(): Long = toEpochMilli()
internal fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

// server.db

internal fun DeviceEntity.toDomain() = Device(
    id = DeviceId.of(deviceUid),
    name = name,
    platform = Platform.parse(platform),
    appVersion = appVersion,
    protocolVersion = protocolVersion,
    createdAt = createdAt.toInstant(),
    lastSeenAt = lastSeenAt?.toInstant(),
    publicKey = publicKey,
    // An unknown value can only come from a newer app version; refusing the device is the safe reading.
    approval = DeviceApproval.entries.firstOrNull { it.name == approval } ?: DeviceApproval.REJECTED,
)

internal fun Device.toEntity() = DeviceEntity(
    deviceUid = id.value,
    name = name,
    platform = platform.name,
    appVersion = appVersion,
    protocolVersion = protocolVersion,
    publicKey = publicKey,
    createdAt = createdAt.millis(),
    lastSeenAt = lastSeenAt?.millis(),
    lastLocationId = null,
    approval = approval.name,
)

internal fun LocationEntity.toDomain(deviceUid: String) = Location(
    id = LocationId.of(clientId),
    deviceId = DeviceId.of(deviceUid),
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    provider = provider,
    batteryPct = batteryPct,
    isMock = isMock,
    recordedAt = recordedAt.toInstant(),
    receivedAt = receivedAt.toInstant(),
)

internal fun Location.toEntity(deviceKey: Long, sessionKey: Long?) = LocationEntity(
    clientId = id.value,
    deviceId = deviceKey,
    sessionId = sessionKey,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    provider = provider,
    batteryPct = batteryPct,
    isMock = isMock,
    recordedAt = recordedAt.millis(),
    receivedAt = checkNotNull(receivedAt) { "A stored location needs its arrival time" }.millis(),
)

internal fun DeviceWithLastLocationRow.toDomain() =
    DeviceWithLastLocation(device.toDomain(), lastLocation?.toDomain(device.deviceUid))

internal fun SessionRow.toDomain() = DeviceSession(
    id = SessionId.of(session.sessionUid),
    deviceId = DeviceId.of(deviceUid),
    startedAt = session.startedAt.toInstant(),
    lastActivityAt = session.lastActivityAt.toInstant(),
    endedAt = session.endedAt?.toInstant(),
    endReason = session.endReason?.let(SessionEndReason::valueOf),
    remoteAddress = session.remoteAddress,
    appVersion = session.appVersion,
    locationsReceived = session.locationsReceived,
)

// tracker.db

internal fun OutboxLocationEntity.toDomain() = PendingLocation(
    location = Location(
        id = LocationId.of(clientId),
        deviceId = DeviceId.of(deviceUid),
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        provider = provider,
        batteryPct = batteryPct,
        isMock = isMock,
        recordedAt = recordedAt.toInstant(),
    ),
    attempts = attempts,
    lastAttemptAt = lastAttemptAt?.toInstant(),
    lastError = lastError,
)

internal fun Location.toOutboxEntity() = OutboxLocationEntity(
    clientId = id.value,
    deviceUid = deviceId.value,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    provider = provider,
    batteryPct = batteryPct,
    isMock = isMock,
    recordedAt = recordedAt.millis(),
)
