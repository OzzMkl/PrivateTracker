package org.privatetracker.tools.simulator

import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration

/** Identifies one location in the ledger and in server.db. */
data class LocationKey(val deviceId: String, val locationId: String)

/** A position as the tracker generated it: server.db must hold exactly these values. */
data class LocationRecord(val key: LocationKey, val recordedAtMs: Long, val latitude: Double, val longitude: Double)

/** How a run ended. A ledger without it belongs to a simulator that was killed or crashed. */
data class RunEnd(val elapsed: Duration, val stoppedEarly: Boolean, val drained: Boolean)

/**
 * Append-only CSV of a run: every simulated device, every position generated and every answer the
 * server gave about one. Each call is flushed at once, so an interrupted run can still be verified.
 */
class Ledger private constructor(private val writer: BufferedWriter) : Closeable {

    enum class Event { DEVICE, GENERATED, ACCEPTED, DUPLICATE, REJECTED, DROPPED, END }

    @Synchronized
    fun device(id: DeviceId, name: String) {
        write(Event.DEVICE, id.value, detail = name)
        writer.flush()
    }

    @Synchronized
    fun generated(location: Location) {
        write(
            Event.GENERATED,
            location.deviceId.value,
            location.id.value,
            location.recordedAt.toEpochMilli().toString(),
            location.latitude.toString(),
            location.longitude.toString(),
        )
        writer.flush()
    }

    @Synchronized
    fun answered(deviceId: DeviceId, result: LocationBatchResult) {
        result.accepted.forEach { write(Event.ACCEPTED, deviceId.value, it.value) }
        result.duplicates.forEach { write(Event.DUPLICATE, deviceId.value, it.value) }
        result.rejected.forEach { write(Event.REJECTED, deviceId.value, it.id, detail = "${it.reason}: ${it.detail}") }
        writer.flush()
    }

    /** Positions a full outbox threw away to make room. */
    @Synchronized
    fun dropped(locations: List<Location>) {
        locations.forEach { write(Event.DROPPED, it.deviceId.value, it.id.value) }
        writer.flush()
    }

    @Synchronized
    fun finished(end: RunEnd) {
        write(
            Event.END,
            deviceId = "",
            detail = "elapsed_ms=${end.elapsed.toMillis()} stopped_early=${end.stoppedEarly} drained=${end.drained}",
        )
        writer.flush()
    }

    @Synchronized
    override fun close() = writer.close()

    private fun write(
        event: Event,
        deviceId: String,
        locationId: String = "",
        recordedAtMs: String = "",
        latitude: String = "",
        longitude: String = "",
        detail: String = "",
    ) {
        val safeDetail = detail.replace(UNSAFE, " ")
        writer.write(listOf(event.name.lowercase(), deviceId, locationId, recordedAtMs, latitude, longitude, safeDetail).joinToString(","))
        writer.newLine()
    }

    companion object {
        const val FILE_NAME = "ledger.csv"
        private const val HEADER = "event,device_id,location_id,recorded_at_ms,latitude,longitude,detail"
        private const val COLUMNS = 7
        private val UNSAFE = Regex("[,\\r\\n]")

        /** Starts the ledger of a new run in [dir]; never appends to an earlier run. */
        fun create(dir: Path): Ledger {
            Files.createDirectories(dir)
            val file = dir.resolve(FILE_NAME)
            if (Files.exists(file)) throw UsageException("Ya existe $file; usa otro --out para no mezclar corridas")
            val writer = Files.newBufferedWriter(file, CREATE_NEW, WRITE)
            writer.write(HEADER)
            writer.newLine()
            writer.flush()
            return Ledger(writer)
        }

        /** Folds a ledger into one entry per location. A cut-off last line, left by a killed run, is ignored. */
        fun read(file: Path): LedgerContents {
            if (!Files.isRegularFile(file)) throw UsageException("No existe el ledger $file")
            val devices = linkedMapOf<String, String>()
            val generated = linkedMapOf<LocationKey, LocationRecord>()
            val acknowledged = hashSetOf<LocationKey>()
            val rejected = hashMapOf<LocationKey, String>()
            val dropped = hashSetOf<LocationKey>()
            var end: RunEnd? = null

            val lines = Files.readAllLines(file).drop(1).filter { it.isNotBlank() }
            lines.forEachIndexed { index, line ->
                val fields = line.split(",", limit = COLUMNS)
                val event = Event.entries.firstOrNull { it.name.lowercase() == fields[0] }
                if (fields.size < COLUMNS || event == null) {
                    if (index == lines.lastIndex) return@forEachIndexed
                    throw UsageException("Línea ${index + 2} inválida en $file: $line")
                }
                val key = LocationKey(fields[1], fields[2])
                when (event) {
                    Event.DEVICE -> devices[fields[1]] = fields[6]
                    Event.GENERATED -> generated[key] = LocationRecord(key, fields[3].toLong(), fields[4].toDouble(), fields[5].toDouble())
                    Event.ACCEPTED, Event.DUPLICATE -> acknowledged += key
                    Event.REJECTED -> rejected[key] = fields[6]
                    Event.DROPPED -> dropped += key
                    Event.END -> end = parseEnd(fields[6])
                }
            }
            return LedgerContents(devices, generated, acknowledged, rejected, dropped, end)
        }

        private fun parseEnd(detail: String): RunEnd {
            val values = detail.split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            return RunEnd(
                elapsed = Duration.ofMillis(values["elapsed_ms"]?.toLongOrNull() ?: 0),
                stoppedEarly = values["stopped_early"].toBoolean(),
                drained = values["drained"].toBoolean(),
            )
        }
    }
}

/** What a ledger says about each location of a run. */
class LedgerContents(
    /** Device id to name. */
    val devices: Map<String, String>,
    val generated: Map<LocationKey, LocationRecord>,
    /** Accepted or reported as duplicate at least once: the server said it holds them. */
    val acknowledged: Set<LocationKey>,
    /** Location to the server's reason. */
    val rejected: Map<LocationKey, String>,
    val dropped: Set<LocationKey>,
    /** Null when the simulator never closed the run. */
    val end: RunEnd?,
)
