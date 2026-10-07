package org.privatetracker.core.domain.export

import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.splitRoute
import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The files a device's history exports to, from 0.6 on. */
enum class HistoryFormat(val extension: String, val mimeType: String) {
    /** GPX 1.1, which map and sports apps read: one track, one segment per stretch without gaps. */
    GPX("gpx", "application/gpx+xml"),

    /** RFC 4180, one row per position with every field, for spreadsheets and scripts. */
    CSV("csv", "text/csv"),
}

/** Writes [locations], oldest first, as [format]. [name] names the track in a GPX file. */
fun writeHistory(format: HistoryFormat, name: String, locations: List<Location>, generatedAt: Instant, out: Writer) {
    when (format) {
        HistoryFormat.GPX -> writeGpx(name, locations, generatedAt, out)
        HistoryFormat.CSV -> writeCsv(locations, out)
    }
    out.flush()
}

/** Element order follows the GPX 1.1 schema (topografix.com/GPX/1/1/gpx.xsd): ele, then time. */
private fun writeGpx(name: String, locations: List<Location>, generatedAt: Instant, out: Writer) {
    val escapedName = xml(name)
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.write("<gpx version=\"1.1\" creator=\"PrivateTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    out.write("  <metadata><name>$escapedName</name><time>${time(generatedAt)}</time></metadata>\n")
    out.write("  <trk>\n    <name>$escapedName</name>\n")
    for (segment in splitRoute(locations, { it.recordedAt }, { it.latitude }, { it.longitude })) {
        out.write("    <trkseg>\n")
        for (location in segment) {
            out.write("      <trkpt lat=\"${plain(location.latitude)}\" lon=\"${plain(location.longitude)}\">")
            location.altitudeM?.let { out.write("<ele>${plain(it)}</ele>") }
            out.write("<time>${time(location.recordedAt)}</time></trkpt>\n")
        }
        out.write("    </trkseg>\n")
    }
    out.write("  </trk>\n</gpx>\n")
}

private val CSV_HEADER = listOf(
    "recorded_at", "latitude", "longitude", "accuracy_m", "altitude_m", "speed_mps", "bearing_deg",
    "provider", "battery_pct", "is_mock", "location_id",
)

private fun writeCsv(locations: List<Location>, out: Writer) {
    out.write(CSV_HEADER.joinToString(",") + "\r\n")
    for (location in locations) {
        val row = listOf(
            time(location.recordedAt),
            plain(location.latitude),
            plain(location.longitude),
            location.accuracyM?.let(::plain).orEmpty(),
            location.altitudeM?.let(::plain).orEmpty(),
            location.speedMps?.let(::plain).orEmpty(),
            location.bearingDeg?.let(::plain).orEmpty(),
            text(location.provider.orEmpty()),
            location.batteryPct?.toString().orEmpty(),
            location.isMock.toString(),
            location.id.value,
        )
        out.write(row.joinToString(",") { csv(it) } + "\r\n")
    }
}

private fun time(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

/** Never in exponent form, which GPX's xsd:decimal and many spreadsheets refuse: 0.0001, not 1.0E-4. */
private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun plain(value: Float): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

private fun xml(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c < ' ' && c != '\t' && c != '\n' && c != '\r') append(' ') else append(c)
        }
    }
}

/** A field in quotes when it holds a comma, a quote or a line break, with quotes doubled (RFC 4180). */
private fun csv(field: String): String =
    if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

/**
 * Text a tracker sent, such as the provider name: a spreadsheet must show it, never run it as a
 * formula, so a leading = + - @ gets an apostrophe in front.
 */
private fun text(value: String): String = if (value.firstOrNull() in FORMULA_STARTS) "'$value" else value

private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
