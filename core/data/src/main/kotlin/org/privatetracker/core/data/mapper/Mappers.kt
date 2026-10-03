package org.privatetracker.core.data.mapper

import org.privatetracker.core.database.server.DeviceEntity
import org.privatetracker.core.database.server.DeviceWithLastLocationRow
import org.privatetracker.core.database.server.LocationEntity
import org.privatetracker.core.database.server.SessionRow
import org.privatetracker.core.database.tracker.OutboxLocationEntity
import org.privatetracker.core.datastore.RecordedLocationData
import org.privatetracker.core.datastore.RegistrationData
import org.privatetracker.core.datastore.ServerConfigData
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.model.SessionId
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
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

// DataStore

internal fun TrackerConfigData.toDomain() = TrackerConfig(
    serverUrl = serverUrl,
    deviceName = deviceName,
    intervalSeconds = intervalSeconds,
    minDistanceM = minDistanceM,
    maxAccuracyM = maxAccuracyM,
    priority = LocationPriority.entries.firstOrNull { it.name == priority } ?: LocationPriority.HIGH_ACCURACY,
    batchSize = batchSize,
    maxQueueSize = maxQueueSize,
    startOnBoot = startOnBoot,
    trackingEnabled = trackingEnabled,
)

internal fun TrackerConfig.toData() = TrackerConfigData(
    serverUrl = serverUrl,
    deviceName = deviceName,
    intervalSeconds = intervalSeconds,
    minDistanceM = minDistanceM,
    maxAccuracyM = maxAccuracyM,
    priority = priority.name,
    batchSize = batchSize,
    maxQueueSize = maxQueueSize,
    startOnBoot = startOnBoot,
    trackingEnabled = trackingEnabled,
)

internal fun ServerConfigData.toDomain() = ServerConfig(
    serverName = serverName,
    port = port,
    bindAddress = bindAddress,
    autoStart = autoStart,
    acceptNewDevices = acceptNewDevices,
    onlineThresholdSeconds = onlineThresholdSeconds,
    retentionDays = retentionDays,
    maxBatchSize = maxBatchSize,
    exposeReadApi = exposeReadApi,
)

internal fun ServerConfig.toData() = ServerConfigData(
    serverName = serverName,
    port = port,
    bindAddress = bindAddress,
    autoStart = autoStart,
    acceptNewDevices = acceptNewDevices,
    onlineThresholdSeconds = onlineThresholdSeconds,
    retentionDays = retentionDays,
    maxBatchSize = maxBatchSize,
    exposeReadApi = exposeReadApi,
)

internal fun RegistrationData.toDomain() = TrackerRegistration(serverUrl, maxBatchSize, registeredAtMillis.toInstant())

internal fun TrackerRegistration.toData() = RegistrationData(serverUrl, maxBatchSize, registeredAt.millis())

/** Only what the minimum-distance filter needs survives a restart. */
internal fun RecordedLocationData.toDomain() = Location(
    id = LocationId.of(id),
    deviceId = DeviceId.of(deviceId),
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    recordedAt = recordedAtMillis.toInstant(),
)

internal fun Location.toRecordedData() =
    RecordedLocationData(id.value, deviceId.value, latitude, longitude, accuracyM, recordedAt.millis())
