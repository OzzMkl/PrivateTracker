package org.privatetracker.core.domain.testing

import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.ProtocolVersion
import java.time.Instant

val DEVICE_A: DeviceId = DeviceId.of("6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21")
val DEVICE_B: DeviceId = DeviceId.of("0b8f2d4e-1a3c-4e5f-8a9b-7c6d5e4f3a21")

fun locationId(n: Int): LocationId = LocationId.of("b3e1f7a2-5c4d-4e8f-a1b2-%012d".format(n))

fun aLocation(
    n: Int = 1,
    deviceId: DeviceId = DEVICE_A,
    latitude: Double = 19.4326,
    longitude: Double = -99.1332,
    accuracyM: Float? = 8.5f,
    recordedAt: Instant = T0,
    receivedAt: Instant? = null,
): Location = Location(
    id = locationId(n),
    deviceId = deviceId,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = 2240.0,
    speedMps = 1.2f,
    bearingDeg = 87f,
    provider = "fused",
    batteryPct = 76,
    recordedAt = recordedAt,
    receivedAt = receivedAt,
)

fun aFix(
    latitude: Double = 19.4326,
    longitude: Double = -99.1332,
    accuracyM: Float? = 8.5f,
    recordedAt: Instant = T0,
): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    provider = "fused",
    recordedAt = recordedAt,
)

fun aDevice(
    id: DeviceId = DEVICE_A,
    name: String = "Pixel de Ana",
    lastSeenAt: Instant? = T0,
    approval: DeviceApproval = DeviceApproval.APPROVED,
): Device = Device(
    id = id,
    name = name,
    platform = Platform.ANDROID,
    appVersion = "0.1.0",
    protocolVersion = ProtocolVersion.CURRENT,
    createdAt = T0,
    lastSeenAt = lastSeenAt,
    publicKey = fakePublicKey(id),
    approval = approval,
)

fun aRegistration(
    id: DeviceId = DEVICE_A,
    name: String = "Pixel de Ana",
    protocolVersion: Int = ProtocolVersion.CURRENT,
    publicKey: String = fakePublicKey(id),
): DeviceRegistration = DeviceRegistration(
    deviceId = id,
    name = name,
    platform = Platform.ANDROID,
    appVersion = "0.1.0",
    protocolVersion = protocolVersion,
    publicKey = publicKey,
)
