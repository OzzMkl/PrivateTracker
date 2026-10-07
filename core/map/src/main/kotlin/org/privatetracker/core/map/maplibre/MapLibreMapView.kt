package org.privatetracker.core.map.maplibre

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.privatetracker.core.designsystem.theme.LocalStatusColors
import org.privatetracker.core.map.MapCamera
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.MapPoint
import org.privatetracker.core.map.MapTrack
import org.privatetracker.core.map.MarkerStyle
import org.privatetracker.core.map.TileSourceConfig
import kotlin.coroutines.resume

private const val MARKER_SOURCE_ID = "markers"
private const val MARKER_LAYER_ID = "markers"
private const val TRACK_SOURCE_ID = "tracks"
private const val TRACK_LAYER_ID = "tracks"
private const val PROP_ID = "id"
private const val PROP_STYLE = "style"
private const val PROP_SELECTED = "selected"
private const val SINGLE_MARKER_ZOOM = 15.0

@Immutable
private data class MarkerColors(val online: Int, val stale: Int, val offline: Int, val self: Int, val track: Int)

/**
 * MapLibre's MapView inside Compose. Markers are circles in a GeoJSON layer rather than symbols,
 * because symbols need a glyph server and the style uses raster tiles only.
 *
 * MapLibre identifies itself to the tile server with the app's package name and version, which
 * satisfies the OpenStreetMap tile policy's request for an identifying User-Agent.
 */
@Composable
internal fun MapLibreMapView(
    markers: List<MapMarker>,
    tracks: List<MapTrack>,
    onTracksDrawn: () -> Unit,
    camera: MapCamera,
    selectedId: String?,
    interactive: Boolean,
    tiles: TileSourceConfig,
    onMarkerClick: (String) -> Unit,
    onMapClick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val touchSlop = with(LocalDensity.current) { 24.dp.toPx() }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).attributionEnabled(false).logoEnabled(false))
    }
    val statusColors = LocalStatusColors.current
    val colors = MarkerColors(
        online = statusColors.positive.toArgb(),
        stale = statusColors.warning.toArgb(),
        offline = statusColors.neutral.toArgb(),
        self = MaterialTheme.colorScheme.primary.toArgb(),
        track = MaterialTheme.colorScheme.primary.toArgb(),
    )
    val currentOnMarkerClick by rememberUpdatedState(onMarkerClick)
    val currentOnTracksDrawn by rememberUpdatedState(onTracksDrawn)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    MapViewLifecycle(mapView, lifecycleOwner)

    LaunchedEffect(mapView) {
        val ready = mapView.awaitMap()
        ready.uiSettings.isRotateGesturesEnabled = false
        ready.uiSettings.isTiltGesturesEnabled = false
        ready.uiSettings.isCompassEnabled = false
        ready.addOnMapClickListener { latLng ->
            val point = ready.projection.toScreenLocation(latLng)
            val area = RectF(point.x - touchSlop, point.y - touchSlop, point.x + touchSlop, point.y + touchSlop)
            val id = ready.queryRenderedFeatures(area, MARKER_LAYER_ID).firstNotNullOfOrNull { it.getStringProperty(PROP_ID) }
            if (id != null) currentOnMarkerClick(id) else currentOnMapClick()
            true
        }
        map = ready
    }

    LaunchedEffect(map, tiles, colors) {
        val ready = map ?: return@LaunchedEffect
        style = null
        style = ready.awaitStyle(
            Style.Builder()
                .fromJson(rasterStyleJson(tiles))
                .withSource(GeoJsonSource(TRACK_SOURCE_ID))
                .withLayer(trackLayer(colors))
                .withSource(GeoJsonSource(MARKER_SOURCE_ID))
                .withLayer(markerLayer(colors)),
        )
    }

    LaunchedEffect(map, interactive) {
        map?.uiSettings?.setAllGesturesEnabled(interactive)
        map?.uiSettings?.isRotateGesturesEnabled = false
        map?.uiSettings?.isTiltGesturesEnabled = false
    }

    LaunchedEffect(style, markers, selectedId) {
        style?.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)?.setGeoJson(markers.toFeatures(selectedId))
    }

    LaunchedEffect(style, tracks) {
        val source = style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID) ?: return@LaunchedEffect
        source.setGeoJson(tracks.toFeatures())
        if (tracks.isNotEmpty()) {
            // MapLibre cuts the line into tiles on a worker thread: drawn means the first complete frame after that.
            mapView.awaitFullyRenderedFrame()
            currentOnTracksDrawn()
        }
    }

    // Fitting follows the set of markers and tracks, not their positions, so a device that moves does not undo the user's panning.
    val cameraKey: Any = if (camera is MapCamera.FitMarkers) markers.map { it.id }.toSet() to tracks else camera
    LaunchedEffect(style, cameraKey) {
        val ready = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        mapView.awaitLayout()
        val points = markers.map { MapPoint(it.latitude, it.longitude) } + tracks.flatMap { track -> track.segments.flatten() }
        ready.moveTo(camera, points, padding = (touchSlop * 2).toInt())
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** Forwards the screen's lifecycle to the MapView and destroys it when it leaves the composition. */
@Composable
private fun MapViewLifecycle(mapView: MapView, owner: LifecycleOwner) {
    DisposableEffect(mapView, owner) {
        var reached = Lifecycle.State.INITIALIZED
        var created = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    mapView.onCreate(null)
                    created = true
                }
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
            reached = event.targetState
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (reached.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (reached.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }
}

private fun trackLayer(colors: MarkerColors): LineLayer =
    LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
        lineColor(colors.track),
        lineWidth(4f),
        lineOpacity(0.85f),
        lineJoin(Property.LINE_JOIN_ROUND),
        lineCap(Property.LINE_CAP_ROUND),
    )

private fun markerLayer(colors: MarkerColors): CircleLayer =
    CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
        circleColor(
            match(
                get(PROP_STYLE),
                color(colors.offline),
                stop(MarkerStyle.ONLINE.name, color(colors.online)),
                stop(MarkerStyle.STALE.name, color(colors.stale)),
                stop(MarkerStyle.SELF.name, color(colors.self)),
                stop(MarkerStyle.ROUTE_START.name, color(colors.offline)),
                stop(MarkerStyle.ROUTE_END.name, color(colors.track)),
            ),
        ),
        circleRadius(switchCase(eq(get(PROP_SELECTED), true), literal(11f), literal(8f))),
        circleStrokeColor(android.graphics.Color.WHITE),
        circleStrokeWidth(2f),
    )

private fun List<MapMarker>.toFeatures(selectedId: String?): FeatureCollection =
    FeatureCollection.fromFeatures(
        map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)).apply {
                addStringProperty(PROP_ID, marker.id)
                addStringProperty(PROP_STYLE, marker.style.name)
                addBooleanProperty(PROP_SELECTED, marker.id == selectedId)
            }
        },
    )

/** Each track as one MultiLineString, so stretches are not joined. */
private fun List<MapTrack>.toFeatures(): FeatureCollection =
    FeatureCollection.fromFeatures(
        map { track ->
            Feature.fromGeometry(
                MultiLineString.fromLngLats(track.segments.map { segment -> segment.map { Point.fromLngLat(it.longitude, it.latitude) } }),
            ).apply { addStringProperty(PROP_ID, track.id) }
        },
    )

private fun MapLibreMap.moveTo(camera: MapCamera, points: List<MapPoint>, padding: Int) {
    when (camera) {
        is MapCamera.Centered -> moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(camera.latitude, camera.longitude), camera.zoom))
        MapCamera.FitMarkers -> {
            val distinct = points.distinct()
            when (distinct.size) {
                0 -> Unit
                1 -> moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(distinct[0].latitude, distinct[0].longitude), SINGLE_MARKER_ZOOM))
                else -> {
                    val bounds = LatLngBounds.Builder().includes(distinct.map { LatLng(it.latitude, it.longitude) }).build()
                    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
                }
            }
        }
    }
}

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { continuation ->
    getMapAsync { continuation.resume(it) }
}

private suspend fun MapLibreMap.awaitStyle(builder: Style.Builder): Style = suspendCancellableCoroutine { continuation ->
    setStyle(builder) { continuation.resume(it) }
}

private suspend fun MapView.awaitFullyRenderedFrame() = suspendCancellableCoroutine { continuation ->
    val listener = object : MapView.OnDidFinishRenderingFrameListener {
        override fun onDidFinishRenderingFrame(fully: Boolean, frameEncodingTime: Double, frameRenderingTime: Double) {
            if (!fully) return
            removeOnDidFinishRenderingFrameListener(this)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    addOnDidFinishRenderingFrameListener(listener)
    continuation.invokeOnCancellation { post { removeOnDidFinishRenderingFrameListener(listener) } }
}

private suspend fun MapView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { continuation -> doOnLayout { continuation.resume(Unit) } }
}
