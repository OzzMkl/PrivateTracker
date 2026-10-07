package org.privatetracker.core.protocol.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire objects of protocol v1. Field names are snake_case; timestamps are ISO 8601 strings in UTC.

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("server_name") val serverName: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("server_time") val serverTime: String,
    /** From 0.3, when the request carried a challenge: the server's key and its signature over the challenge. */
    @SerialName("server_key") val serverKey: String? = null,
    val signature: String? = null,
    /** From 0.5: what trackers seal their requests for, signed with the server's key. */
    @SerialName("encryption_key") val encryptionKey: EncryptionKeyDto? = null,
)

@Serializable
data class EncryptionKeyDto(
    val id: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("use_until") val useUntil: String,
    val signature: String,
)

/** A request body sealed for the server's encryption key [keyId], from 0.5 on. See SealedBodies. */
@Serializable
data class SealedRequestDto(
    @SerialName("key_id") val keyId: String,
    val enc: String,
    val ciphertext: String,
)

/** A response body sealed for the tracker that sent the request, under a random [nonce]. */
@Serializable
data class SealedResponseDto(val nonce: String, val ciphertext: String)

@Serializable
data class RegisterDeviceRequest(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("protocol_version") val protocolVersion: Int,
    /** Required from 0.2 on; optional on the wire so a 0.1 tracker gets a clear validation error. */
    @SerialName("public_key") val publicKey: String? = null,
    /** From 0.3, when the tracker registers from a QR invite. */
    val pairing: PairingClaimDto? = null,
)

@Serializable
data class PairingClaimDto(val ticket: String, val proof: String)

@Serializable
data class RegisterDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    val created: Boolean,
    @SerialName("max_batch_size") val maxBatchSize: Int,
    @SerialName("server_time") val serverTime: String,
    /** PENDING, APPROVED or REJECTED. A 0.1 server sends none and lets every device in. */
    val approval: String = "APPROVED",
)

@Serializable
data class LocationDto(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    val provider: String? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("is_mock") val isMock: Boolean = false,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class LocationBatchRequest(val locations: List<LocationDto>)

@Serializable
data class RejectedLocationDto(val id: String, val code: String, val detail: String)

@Serializable
data class LocationBatchResponse(
    val accepted: List<String>,
    val duplicates: List<String>,
    val rejected: List<RejectedLocationDto>,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class DeviceSummaryDto(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val status: String,
    val approval: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("last_location") val lastLocation: LocationDto? = null,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceSummaryDto>)

@Serializable
data class SessionDto(
    @SerialName("started_at") val startedAt: String,
    @SerialName("last_activity_at") val lastActivityAt: String,
    @SerialName("locations_received") val locationsReceived: Int,
)

@Serializable
data class DeviceDetailDto(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val platform: String,
    val status: String,
    val approval: String? = null,
    @SerialName("key_fingerprint") val keyFingerprint: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("last_location") val lastLocation: LocationDto? = null,
    @SerialName("current_session") val currentSession: SessionDto? = null,
)

/** RFC 9457 problem details, plus the stable [code] and, for BATCH_TOO_LARGE, the limit. */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val code: String,
    @SerialName("max_batch_size") val maxBatchSize: Int? = null,
)
