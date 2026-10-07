package org.privatetracker.core.domain.model

import org.privatetracker.core.domain.geo.distanceMeters
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sqrt

/** A span of time: [from] included, [to] excluded. */
data class TimeRange(val from: Instant, val to: Instant) {
    init {
        require(!to.isBefore(from)) { "A range cannot end before it starts" }
    }
}

/**
 * The ranges the history screen offers at one tap, counted in the phone's time zone. They reach a few
 * minutes past now: the server accepts positions stamped that far ahead by a tracker whose clock runs fast.
 */
enum class HistoryPeriod {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    LAST_30_DAYS;

    fun range(now: Instant, zone: ZoneId): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        return when (this) {
            TODAY -> dayRange(today, today, zone)
            YESTERDAY -> dayRange(today.minusDays(1), today.minusDays(1), zone)
            LAST_7_DAYS -> TimeRange(now.minus(Duration.ofDays(7)), now.plus(CLOCK_SLACK))
            LAST_30_DAYS -> TimeRange(now.minus(Duration.ofDays(30)), now.plus(CLOCK_SLACK))
        }
    }

    private companion object {
        /** As much as LocationValidator lets a tracker's clock run ahead. */
        val CLOCK_SLACK: Duration = Duration.ofMinutes(5)
    }
}

/** Whole calendar days from [first] through [last], in [zone]. */
fun dayRange(first: LocalDate, last: LocalDate, zone: ZoneId): TimeRange =
    TimeRange(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())

/** One point of a route, with only what drawing and measuring it need. */
data class TrackPoint(val latitude: Double, val longitude: Double, val recordedAt: Instant)

/**
 * A device's route over a range, oldest first, cut into stretches where the device went silent for a
 * long time and turned up somewhere else: the map does not join them with a straight line the device
 * never travelled. See [splitRoute].
 */
data class Track(val segments: List<List<TrackPoint>>) {
    val pointCount: Int get() = segments.sumOf { it.size }
    val isEmpty: Boolean get() = segments.isEmpty()
    val start: Instant? get() = segments.firstOrNull()?.firstOrNull()?.recordedAt
    val end: Instant? get() = segments.lastOrNull()?.lastOrNull()?.recordedAt

    /** Along each stretch; the gaps between stretches are not counted. Worked out once, on first use. */
    val distanceM: Double by lazy(LazyThreadSafetyMode.PUBLICATION) {
        segments.sumOf { segment ->
            var sum = 0.0
            for (i in 1 until segment.size) {
                sum += distanceMeters(segment[i - 1].latitude, segment[i - 1].longitude, segment[i].latitude, segment[i].longitude)
            }
            sum
        }
    }

    /**
     * The same route with fewer points: none moves more than [toleranceM] off the line drawn. Fixes
     * closer than that to the last one kept go first, so a phone standing still for hours becomes a
     * single point instead of a scribble; then Douglas–Peucker. For drawing only; distances and
     * exports use every point.
     */
    fun simplified(toleranceM: Double): Track = Track(segments.map { simplify(it, toleranceM) })

    companion object {
        /** The shortest silence that can cut a route, whatever the device's usual spacing. */
        val MAX_GAP: Duration = Duration.ofMinutes(15)

        /** Closer than this, two positions are the same place however long apart: a phone at rest. */
        const val SAME_PLACE_M = 200.0

        /** [points] must be oldest first. */
        fun of(points: List<TrackPoint>): Track = Track(splitRoute(points, { it.recordedAt }, { it.latitude }, { it.longitude }))
    }
}

/**
 * Splits a route, oldest first, where the device went silent for long and turned up more than
 * [Track.SAME_PLACE_M] away. "Long" follows how often it reports: three times its usual spacing, and
 * never under [Track.MAX_GAP], so a tracker set to report every half hour still draws a line, and a
 * phone resting for hours at one place stays one stretch.
 */
fun <T> splitRoute(items: List<T>, time: (T) -> Instant, latitude: (T) -> Double, longitude: (T) -> Double): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    // Plain milliseconds: a month of positions is tens of thousands of them.
    val millis = LongArray(items.size) { time(items[it]).toEpochMilli() }
    val spacings = LongArray(items.size - 1) { millis[it + 1] - millis[it] }.filter { it > 0 }.toLongArray().apply { sort() }
    // The lower median: with few positions, one long silence must not pass for the usual spacing.
    val usual = spacings.getOrElse((spacings.size - 1) / 2) { 0L }
    val maxGap = maxOf(Track.MAX_GAP.toMillis(), usual * 3)
    val segments = mutableListOf<MutableList<T>>(mutableListOf(items.first()))
    for (i in 1 until items.size) {
        val previous = items[i - 1]
        val current = items[i]
        if (millis[i] - millis[i - 1] > maxGap &&
            distanceMeters(latitude(previous), longitude(previous), latitude(current), longitude(current)) > Track.SAME_PLACE_M
        ) {
            segments += mutableListOf<T>()
        }
        segments.last() += current
    }
    return segments
}

/**
 * A radial-distance pass, then Douglas–Peucker, both on a local flat projection, which is exact
 * enough within a city and for a few meters of tolerance. Iterative, so tens of thousands of points
 * cannot overflow the stack.
 */
private fun simplify(all: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
    if (all.size < 3 || toleranceM <= 0.0) return all
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(all.first().latitude))
    val points = ArrayList<TrackPoint>(all.size).apply { add(all.first()) }
    for (i in 1 until all.lastIndex) {
        val dx = (all[i].longitude - points.last().longitude) * metersPerDegreeLon
        val dy = (all[i].latitude - points.last().latitude) * metersPerDegreeLat
        if (dx * dx + dy * dy > toleranceM * toleranceM) points += all[i]
    }
    points += all.last()
    if (points.size < 3) return points
    val x = DoubleArray(points.size) { points[it].longitude * metersPerDegreeLon }
    val y = DoubleArray(points.size) { points[it].latitude * metersPerDegreeLat }
    val keep = BooleanArray(points.size).apply {
        this[0] = true
        this[lastIndex] = true
    }
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
    while (stack.isNotEmpty()) {
        val (first, last) = stack.removeLast()
        var farthest = -1
        var farthestDistance = toleranceM
        for (i in first + 1 until last) {
            val d = distanceToSegment(x[i], y[i], x[first], y[first], x[last], y[last])
            if (d > farthestDistance) {
                farthest = i
                farthestDistance = d
            }
        }
        if (farthest != -1) {
            keep[farthest] = true
            stack.add(first to farthest)
            stack.add(farthest to last)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val cx = ax + t * dx - px
    val cy = ay + t * dy - py
    return sqrt(cx * cx + cy * cy)
}
