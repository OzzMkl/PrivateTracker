package org.privatetracker.feature.settings

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig

// Editable copies of the settings. Numbers stay text while typed; field names match the domain validators.

data class TrackerForm(
    val serverUrl: String,
    val deviceName: String,
    val intervalSeconds: String,
    val minDistanceM: String,
    val maxAccuracyM: String,
    val priority: LocationPriority,
    val startOnBoot: Boolean,
    val batchSize: String,
    val maxQueueSize: String,
    val errors: Map<String, FieldViolation> = emptyMap(),
    val dirty: Boolean = false,
) {
    /** [base] supplies what the form does not edit, such as whether tracking is on. */
    fun toConfig(base: TrackerConfig): Pair<TrackerConfig, List<FieldViolation>> {
        val numbers = NumberReader()
        val config = base.copy(
            serverUrl = serverUrl,
            deviceName = deviceName,
            intervalSeconds = numbers.int("intervalSeconds", intervalSeconds),
            minDistanceM = numbers.float("minDistanceM", minDistanceM),
            maxAccuracyM = numbers.float("maxAccuracyM", maxAccuracyM),
            priority = priority,
            startOnBoot = startOnBoot,
            batchSize = numbers.int("batchSize", batchSize),
            maxQueueSize = numbers.int("maxQueueSize", maxQueueSize),
        )
        return config to numbers.violations
    }

    companion object {
        fun from(config: TrackerConfig) = TrackerForm(
            serverUrl = config.serverUrl,
            deviceName = config.deviceName,
            intervalSeconds = config.intervalSeconds.toString(),
            minDistanceM = config.minDistanceM.plain(),
            maxAccuracyM = config.maxAccuracyM.plain(),
            priority = config.priority,
            startOnBoot = config.startOnBoot,
            batchSize = config.batchSize.toString(),
            maxQueueSize = config.maxQueueSize.toString(),
        )
    }
}

data class ServerForm(
    val serverName: String,
    val port: String,
    val bindAddress: String,
    val autoStart: Boolean,
    val acceptNewDevices: Boolean,
    val onlineThresholdSeconds: String,
    val retentionDays: String,
    val maxBatchSize: String,
    val exposeReadApi: Boolean,
    val errors: Map<String, FieldViolation> = emptyMap(),
    val dirty: Boolean = false,
) {
    fun toConfig(base: ServerConfig): Pair<ServerConfig, List<FieldViolation>> {
        val numbers = NumberReader()
        val config = base.copy(
            serverName = serverName,
            port = numbers.int("port", port),
            bindAddress = bindAddress,
            autoStart = autoStart,
            acceptNewDevices = acceptNewDevices,
            onlineThresholdSeconds = numbers.int("onlineThresholdSeconds", onlineThresholdSeconds),
            retentionDays = numbers.int("retentionDays", retentionDays),
            maxBatchSize = numbers.int("maxBatchSize", maxBatchSize),
            exposeReadApi = exposeReadApi,
        )
        return config to numbers.violations
    }

    companion object {
        fun from(config: ServerConfig) = ServerForm(
            serverName = config.serverName,
            port = config.port.toString(),
            bindAddress = config.bindAddress,
            autoStart = config.autoStart,
            acceptNewDevices = config.acceptNewDevices,
            onlineThresholdSeconds = config.onlineThresholdSeconds.toString(),
            retentionDays = config.retentionDays.toString(),
            maxBatchSize = config.maxBatchSize.toString(),
            exposeReadApi = config.exposeReadApi,
        )
    }
}

/** Reads numbers typed by the user; what does not parse becomes a violation and a placeholder value. */
private class NumberReader {
    val violations = mutableListOf<FieldViolation>()

    fun int(field: String, text: String): Int = text.trim().toIntOrNull() ?: invalid(field, 0)

    /** Accepts a decimal comma, as many keyboards in Spanish type it. */
    fun float(field: String, text: String): Float = text.trim().replace(',', '.').toFloatOrNull() ?: invalid(field, 0f)

    private fun <T> invalid(field: String, placeholder: T): T {
        violations += FieldViolation(field, FieldViolation.INVALID_FORMAT)
        return placeholder
    }
}

/** 0.0 as "0" and 12.5 as "12.5". */
private fun Float.plain(): String = if (this % 1f == 0f) toInt().toString() else toString()
