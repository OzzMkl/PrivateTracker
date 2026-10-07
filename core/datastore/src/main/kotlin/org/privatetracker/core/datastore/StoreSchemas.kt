package org.privatetracker.core.datastore

import kotlinx.serialization.Serializable

// What each store persists. Separate from the domain models so the domain carries no serialization
// annotations; core:data maps between both. Defaults must match the domain defaults.

@Serializable
data class TrackerConfigData(
    val serverUrl: String = "",
    val deviceName: String = "",
    val intervalSeconds: Int = 60,
    val minDistanceM: Float = 0f,
    val maxAccuracyM: Float = 100f,
    val priority: String = "HIGH_ACCURACY",
    val batchSize: Int = 50,
    val maxQueueSize: Int = 10_000,
    val startOnBoot: Boolean = false,
    val trackingEnabled: Boolean = false,
    val serverKey: String = "",
    val serverAddresses: List<String> = emptyList(),
    val serverFingerprint: String = "",
)

@Serializable
data class ServerConfigData(
    val serverName: String = "PrivateTracker Server",
    val port: Int = 8787,
    val bindAddress: String = "0.0.0.0",
    val autoStart: Boolean = false,
    val acceptNewDevices: Boolean = true,
    val onlineThresholdSeconds: Int = 300,
    val retentionDays: Int = 30,
    val maxBatchSize: Int = 100,
    val exposeReadApi: Boolean = false,
    val advertiseOnLan: Boolean = true,
)

@Serializable
data class IdentityData(val deviceId: String? = null)

/** [mode] is an AppMode name; null until onboarding ends. */
@Serializable
data class AppModeData(val mode: String? = null)

@Serializable
data class TrackerStateData(
    val registration: RegistrationData? = null,
    val lastRecorded: RecordedLocationData? = null,
)

@Serializable
data class RegistrationData(val serverUrl: String, val maxBatchSize: Int, val registeredAtMillis: Long)

@Serializable
data class RecordedLocationData(
    val id: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float? = null,
    val recordedAtMillis: Long,
)
