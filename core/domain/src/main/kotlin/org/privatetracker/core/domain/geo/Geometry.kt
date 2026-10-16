package org.privatetracker.core.domain.geo

import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.GeofenceShape
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Geofence geometry, from 0.7 on. Polygons are measured on a flat projection around the point in
// question: exact to well under a meter within the tens of kilometers a geofence spans. Shapes that
// cross the 180th meridian are not supported.

private const val EARTH_MEAN_RADIUS_M = 6_371_008.8
private const val METERS_PER_DEGREE_LAT = 111_320.0
private const val DEPTH_PRECISION_M = 0.5
private const val MAX_CELLS_PER_SIDE = 64
private const val MAX_DEPTH_PROBES = 20_000

/** Meters from [point] to the edge of [shape]: positive inside, negative outside. */
fun signedDistanceToEdge(shape: GeofenceShape, point: GeoPoint): Double = when (shape) {
    is GeofenceShape.Circle -> shape.radiusM - distanceMeters(shape.center.latitude, shape.center.longitude, point.latitude, point.longitude)
    is GeofenceShape.Polygon -> polygonSignedDistance(shape.vertices, point)
}

/**
 * How far the deepest point of [shape] lies from its edge: the radius of the largest circle that fits
 * inside. A circle's own radius; a thin polygon is shallow however large its area, and margins must
 * stay below this or no position could ever count as inside.
 */
fun insideDepthM(shape: GeofenceShape): Double = when (shape) {
    is GeofenceShape.Circle -> shape.radiusM
    is GeofenceShape.Polygon -> polygonDepth(shape.vertices)
}

/** The radius of a circle as large as [shape]: how big it is, to frame it on a map. */
fun equivalentRadiusM(shape: GeofenceShape): Double = when (shape) {
    is GeofenceShape.Circle -> shape.radiusM
    is GeofenceShape.Polygon -> sqrt(polygonAreaM2(shape.vertices) / PI)
}

/** Points along the edge of [shape], to draw it; a circle becomes [circleSteps] points. */
fun outline(shape: GeofenceShape, circleSteps: Int = 64): List<GeoPoint> = when (shape) {
    is GeofenceShape.Polygon -> shape.vertices
    is GeofenceShape.Circle -> (0 until circleSteps).map { destination(shape.center, shape.radiusM, 360.0 * it / circleSteps) }
}

/** Area in square meters of a polygon, by the shoelace formula on a flat projection. */
fun polygonAreaM2(vertices: List<GeoPoint>): Double {
    if (vertices.size < 3) return 0.0
    val (x, y) = project(vertices, vertices.first())
    var twice = 0.0
    for (i in vertices.indices) {
        val j = (i + 1) % vertices.size
        twice += x[i] * y[j] - x[j] * y[i]
    }
    return abs(twice) / 2
}

/** True when two edges that do not share a vertex cross: such a polygon has no clear inside. */
fun isSelfIntersecting(vertices: List<GeoPoint>): Boolean {
    val n = vertices.size
    if (n < 4) return false
    val (x, y) = project(vertices, vertices.first())
    for (i in 0 until n) {
        val i2 = (i + 1) % n
        for (j in i + 1 until n) {
            val j2 = (j + 1) % n
            if (j == i2 || j2 == i) continue
            if (segmentsCross(x[i], y[i], x[i2], y[i2], x[j], y[j], x[j2], y[j2])) return true
        }
    }
    return false
}

/** The point [distanceM] away from [from] along [bearingDeg], on a sphere. */
fun destination(from: GeoPoint, distanceM: Double, bearingDeg: Double): GeoPoint {
    val delta = distanceM / EARTH_MEAN_RADIUS_M
    val theta = Math.toRadians(bearingDeg)
    val phi1 = Math.toRadians(from.latitude)
    val lambda1 = Math.toRadians(from.longitude)
    val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
    val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
    return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
}

private fun polygonSignedDistance(vertices: List<GeoPoint>, point: GeoPoint): Double {
    // The point sits at the origin of the projection.
    val (x, y) = project(vertices, point)
    return planarSignedDistance(x, y, 0.0, 0.0)
}

/** From (px, py) to the edge of the polygon (x, y) on the plane: positive inside, negative outside. */
private fun planarSignedDistance(x: DoubleArray, y: DoubleArray, px: Double, py: Double): Double {
    var inside = false
    var nearest = Double.MAX_VALUE
    for (i in x.indices) {
        val j = (i + x.size - 1) % x.size
        // Even-odd ray casting along +x from the point.
        if ((y[i] > py) != (y[j] > py) && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) inside = !inside
        nearest = minOf(nearest, distanceToSegment(px, py, x[j], y[j], x[i], y[i]))
    }
    return if (inside) nearest else -nearest
}

/**
 * The pole of inaccessibility, searched as Mapbox's polylabel does: square cells over the bounding box,
 * the most promising first (a cell can hold no point deeper than its center's depth plus its half
 * diagonal), split in four until none can beat the best by more than [DEPTH_PRECISION_M].
 */
private fun polygonDepth(vertices: List<GeoPoint>): Double {
    if (vertices.size < 3) return 0.0
    val (x, y) = project(vertices, vertices.first())
    val (minX, maxX) = x.min() to x.max()
    val (minY, maxY) = y.min() to y.max()
    val width = maxX - minX
    val height = maxY - minY
    if (minOf(width, height) <= 0.0) return 0.0

    class Cell(val cx: Double, val cy: Double, val half: Double) {
        val depth = planarSignedDistance(x, y, cx, cy)
        val bound = depth + half * sqrt(2.0)
    }
    val queue = PriorityQueue<Cell>(compareByDescending { it.bound })
    // Cells as wide as the shorter side, but not so small that a long thin strip needs thousands.
    val size = maxOf(minOf(width, height), maxOf(width, height) / MAX_CELLS_PER_SIDE)
    var cellX = minX
    while (cellX < maxX) {
        var cellY = minY
        while (cellY < maxY) {
            queue += Cell(cellX + size / 2, cellY + size / 2, size / 2)
            cellY += size
        }
        cellX += size
    }
    var best = Cell(minX + width / 2, minY + height / 2, 0.0)
    var probes = queue.size
    while (probes < MAX_DEPTH_PROBES) {
        val cell = queue.poll() ?: break
        if (cell.depth > best.depth) best = cell
        // The queue is ordered by bound: no cell left can do better.
        if (cell.bound - best.depth <= DEPTH_PRECISION_M) break
        val quarter = cell.half / 2
        for (dx in doubleArrayOf(-quarter, quarter)) {
            for (dy in doubleArrayOf(-quarter, quarter)) queue += Cell(cell.cx + dx, cell.cy + dy, quarter)
        }
        probes += 4
    }
    return best.depth.coerceAtLeast(0.0)
}

private fun project(points: List<GeoPoint>, origin: GeoPoint): Pair<DoubleArray, DoubleArray> {
    val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.latitude))
    val x = DoubleArray(points.size) { (points[it].longitude - origin.longitude) * metersPerDegreeLon }
    val y = DoubleArray(points.size) { (points[it].latitude - origin.latitude) * METERS_PER_DEGREE_LAT }
    return x to y
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

private fun segmentsCross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
    fun orientation(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Double =
        (qx - px) * (ry - py) - (qy - py) * (rx - px)
    val d1 = orientation(cx, cy, dx, dy, ax, ay)
    val d2 = orientation(cx, cy, dx, dy, bx, by)
    val d3 = orientation(ax, ay, bx, by, cx, cy)
    val d4 = orientation(ax, ay, bx, by, dx, dy)
    return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0.0 && d2 != 0.0 && d3 != 0.0 && d4 != 0.0
}
