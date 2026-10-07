package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.export.HistoryFormat
import org.privatetracker.core.domain.export.writeHistory
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.Track
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.LocationRepository
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId

/** A device's route over a range, from 0.6 on, for the history map. */
class GetDeviceTrack(
    private val devices: DeviceRepository,
    private val locations: LocationRepository,
) {
    suspend operator fun invoke(deviceId: DeviceId, range: TimeRange): Outcome<Track> {
        devices.get(deviceId) ?: return DomainError.DeviceNotFound.asFailure()
        return Track.of(locations.findTrackPoints(deviceId, range)).asSuccess()
    }
}

/**
 * Writes a device's history over a range to [invoke]'s writer, as GPX or CSV. The file only ever
 * goes where the owner saves it. Returns how many positions it holds; the writer's own IOException
 * reaches the caller, which chose where to write.
 */
class ExportDeviceHistory(
    private val devices: DeviceRepository,
    private val locations: LocationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(deviceId: DeviceId, range: TimeRange, format: HistoryFormat, out: Writer): Outcome<Int> {
        val device = devices.get(deviceId) ?: return DomainError.DeviceNotFound.asFailure()
        val recorded = locations.findRecorded(deviceId, range)
        writeHistory(format, device.name, recorded, clock.now(), out)
        return recorded.size.asSuccess()
    }
}

/** `PrivateTracker-Pixel_de_Ana-2026-10-01_2026-10-07.gpx`: safe on every file system, sorted by date. */
fun historyFileName(deviceName: String, range: TimeRange, format: HistoryFormat, zone: ZoneId): String {
    val name = deviceName.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(MAX_NAME).ifEmpty { "dispositivo" }
    val first: LocalDate = range.from.atZone(zone).toLocalDate()
    // The range ends just before its last instant's day when it stops at midnight.
    val last: LocalDate = range.to.minusMillis(1).atZone(zone).toLocalDate().coerceAtLeast(first)
    val days = if (first == last) "$first" else "${first}_$last"
    return "PrivateTracker-$name-$days.${format.extension}"
}

private const val MAX_NAME = 40
