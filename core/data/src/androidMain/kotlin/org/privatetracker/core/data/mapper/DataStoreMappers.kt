package org.privatetracker.core.data.mapper

import org.privatetracker.core.datastore.RecordedLocationData
import org.privatetracker.core.datastore.RegistrationData
import org.privatetracker.core.datastore.ServerConfigData
import org.privatetracker.core.datastore.StoredEncryptionKeyData
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.datastore.TrustedEncryptionKeyData
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.StoredEncryptionKey
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.model.TrustedEncryptionKey

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
    serverKey = serverKey,
    serverAddresses = serverAddresses,
    serverFingerprint = serverFingerprint,
    adaptiveInterval = adaptiveInterval,
    stillIntervalSeconds = stillIntervalSeconds,
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
    serverKey = serverKey,
    serverAddresses = serverAddresses,
    serverFingerprint = serverFingerprint,
    adaptiveInterval = adaptiveInterval,
    stillIntervalSeconds = stillIntervalSeconds,
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
    advertiseOnLan = advertiseOnLan,
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
    advertiseOnLan = advertiseOnLan,
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

internal fun TrustedEncryptionKeyData.toDomain() =
    TrustedEncryptionKey(EncryptionKey(id, publicKey, useUntilMillis.toInstant()), serverKey)

internal fun TrustedEncryptionKey.toData() =
    TrustedEncryptionKeyData(key.id, key.publicKey, key.useUntil.millis(), serverKey)

// Server encryption keys

internal fun StoredEncryptionKeyData.toDomain() =
    StoredEncryptionKey(EncryptionKey(id, publicKey, useUntilMillis.toInstant()), createdAtMillis.toInstant(), protectedPrivateKey)

internal fun StoredEncryptionKey.toData() =
    StoredEncryptionKeyData(key.id, key.publicKey, key.useUntil.millis(), createdAt.millis(), protectedPrivateKey)
