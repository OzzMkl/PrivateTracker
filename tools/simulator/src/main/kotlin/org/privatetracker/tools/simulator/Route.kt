package org.privatetracker.tools.simulator

import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.port.BatteryLevelProvider
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A phone wandering around [center]: it stands still, walks or drives, turns a little at every fix
 * and heads back once it strays beyond [radiusM]. Accuracy varies like a real GPS.
 */
class RandomRoute(
    private val center: GeoPoint,
    private val random: Random,
    private val radiusM: Double = 3_000.0,
) {
    private var position = move(center, random.nextDouble(0.0, radiusM), random.nextDouble(0.0, 360.0))
    private var bearing = random.nextDouble(0.0, 360.0)
    private var speedMps = pickSpeed()
    private var lastAt: Instant? = null

    fun next(at: Instant): LocationFix {
        val elapsedSeconds = lastAt?.let { (at.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) / 1_000.0 } ?: 0.0
        lastAt = at
        if (random.nextDouble() < SPEED_CHANGE_CHANCE) speedMps = pickSpeed()
        bearing = if (distanceMeters(center.latitude, center.longitude, position.latitude, position.longitude) > radiusM) {
            bearingTo(position, center)
        } else {
            normalize(bearing + random.nextDouble(-MAX_TURN_DEG, MAX_TURN_DEG))
        }
        position = move(position, speedMps * elapsedSeconds, bearing)

        return LocationFix(
            latitude = position.latitude,
            longitude = position.longitude,
            accuracyM = random.nextDouble(3.0, 25.0).toFloat(),
            speedMps = speedMps.toFloat(),
            // Rounding to Float can turn 359.99999 into 360, which validation rejects.
            bearingDeg = bearing.toFloat().takeIf { it < 360f } ?: 0f,
            provider = PROVIDER,
            isMock = true,
            recordedAt = at,
        )
    }

    private fun pickSpeed(): Double {
        val roll = random.nextDouble()
        return when {
            roll < 0.3 -> 0.0
            roll < 0.7 -> random.nextDouble(0.8, 2.0)
            else -> random.nextDouble(5.0, 15.0)
        }
    }

    companion object {
        /** Marks simulated positions in server.db, together with is_mock. */
        const val PROVIDER = "simulator"
        private const val SPEED_CHANGE_CHANCE = 0.1
        private const val MAX_TURN_DEG = 30.0
        private const val EARTH_RADIUS_M = 6_371_008.8

        /** Flat-earth step: plenty for the few hundred meters a phone moves between fixes. */
        internal fun move(from: GeoPoint, distanceM: Double, bearingDeg: Double): GeoPoint {
            val bearingRad = Math.toRadians(bearingDeg)
            val dLat = distanceM * cos(bearingRad) / EARTH_RADIUS_M
            val dLon = distanceM * sin(bearingRad) / (EARTH_RADIUS_M * cos(Math.toRadians(from.latitude)))
            return GeoPoint(from.latitude + Math.toDegrees(dLat), from.longitude + Math.toDegrees(dLon))
        }

        internal fun bearingTo(from: GeoPoint, to: GeoPoint): Double {
            val dNorth = to.latitude - from.latitude
            val dEast = (to.longitude - from.longitude) * cos(Math.toRadians(from.latitude))
            return normalize(Math.toDegrees(atan2(dEast, dNorth)))
        }

        private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
    }
}

/**
 * A month in the life of one phone, for the history: home at night, a drive to work and back on
 * weekdays, a walk at lunch, and an outing on weekends. Indoors the fixes wander a few meters, as
 * real ones do. The same seed gives the same month.
 */
class DailyRoutine(center: GeoPoint, private val random: Random, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val home = RandomRoute.move(center, random.nextDouble(500.0, 2_000.0), random.nextDouble(0.0, 360.0))
    private val work = RandomRoute.move(home, random.nextDouble(4_000.0, 8_000.0), random.nextDouble(0.0, 360.0))

    fun at(time: Instant): LocationFix {
        val local = time.atZone(zone)
        val minute = local.hour * 60 + local.minute + local.second / 60.0
        val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
        val (place, speed) = if (weekend) weekendAt(minute) else weekdayAt(minute)
        val still = speed == 0.0
        val noisy = if (still) RandomRoute.move(place, random.nextDouble(0.0, 8.0), random.nextDouble(0.0, 360.0)) else place
        return LocationFix(
            latitude = noisy.latitude,
            longitude = noisy.longitude,
            accuracyM = (if (still) random.nextDouble(8.0, 30.0) else random.nextDouble(3.0, 12.0)).toFloat(),
            speedMps = speed.toFloat(),
            provider = RandomRoute.PROVIDER,
            isMock = true,
            recordedAt = time,
        )
    }

    private fun weekdayAt(minute: Double): Pair<GeoPoint, Double> = when {
        minute < 450 -> home to 0.0
        minute < 490 -> drive(home, work, (minute - 450) / 40)
        minute < 780 -> work to 0.0
        minute < 820 -> loop(work, radiusM = 300.0, fraction = (minute - 780) / 40) to WALK_MPS
        minute < 1050 -> work to 0.0
        minute < 1090 -> drive(work, home, (minute - 1050) / 40)
        else -> home to 0.0
    }

    private fun weekendAt(minute: Double): Pair<GeoPoint, Double> = when {
        minute in 660.0..780.0 -> loop(home, radiusM = 2_000.0, fraction = (minute - 660) / 120) to DRIVE_MPS / 2
        else -> home to 0.0
    }

    /** Along a gently curving road, not a straight line, so simplifying it has turns to keep. */
    private fun drive(from: GeoPoint, to: GeoPoint, fraction: Double): Pair<GeoPoint, Double> {
        val distance = distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val bearing = RandomRoute.bearingTo(from, to)
        val along = RandomRoute.move(from, distance * fraction, bearing)
        val sideways = sin(fraction * Math.PI * 3) * 400.0
        return RandomRoute.move(along, sideways, bearing + 90.0) to distance / (40 * 60)
    }

    private fun loop(around: GeoPoint, radiusM: Double, fraction: Double): GeoPoint {
        val angle = fraction * 360.0
        val center = RandomRoute.move(around, radiusM, 0.0)
        return RandomRoute.move(center, radiusM, angle + 180.0)
    }

    private companion object {
        const val WALK_MPS = 1.3
        const val DRIVE_MPS = 8.0
    }
}

/** Drains a point now and then and recharges once it gets low, so battery_pct changes over a long run. */
class SimulatedBattery(private val random: Random) : BatteryLevelProvider {
    private var level = random.nextInt(40, 101)

    @Synchronized
    override fun currentLevelPct(): Int {
        if (random.nextDouble() < DRAIN_CHANCE) level -= 1
        if (level < RECHARGE_BELOW) level = 100
        return level
    }

    private companion object {
        const val DRAIN_CHANCE = 0.05
        const val RECHARGE_BELOW = 15
    }
}
