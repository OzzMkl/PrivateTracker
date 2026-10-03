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
)

@Serializable
data class RegisterDeviceRequest(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("protocol_version") val protocolVersion: Int,
)

@Serializable
data class RegisterDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    val created: Boolean,
    @SerialName("max_batch_size") val maxBatchSize: Int,
    @SerialName("server_time") val serverTime: String,
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
