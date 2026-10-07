package org.privatetracker.core.map

import androidx.compose.runtime.Immutable

enum class MarkerStyle { ONLINE, STALE, OFFLINE, SELF, ROUTE_START, ROUTE_END }

/** A point on the map. [id] comes back in the click callback. */
@Immutable
data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val style: MarkerStyle,
)

@Immutable
data class MapPoint(val latitude: Double, val longitude: Double)

/** A route drawn as a line, one per stretch: [segments] are not joined to each other. */
@Immutable
data class MapTrack(val id: String, val segments: List<List<MapPoint>>)

/**
 * Where the map looks. The camera moves when this value changes, and for [FitMarkers] when markers
 * or tracks come or go.
 */
sealed interface MapCamera {
    /** Shows every marker and track; a single marker is shown up close. */
    data object FitMarkers : MapCamera

    data class Centered(val latitude: Double, val longitude: Double, val zoom: Double = 15.0) : MapCamera
}

/**
 * Raster tiles and the attribution their license requires on screen. The URL can point to a
 * self-hosted tile server instead of OpenStreetMap's, whose policy forbids heavy use.
 */
@Immutable
data class TileSourceConfig(
    val urlTemplate: String,
    val attribution: String,
    val attributionUrl: String,
    val maxZoom: Int,
) {
    companion object {
        val OpenStreetMap = TileSourceConfig(
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            maxZoom = 19,
        )
    }
}
