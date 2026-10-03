package org.privatetracker.core.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.privatetracker.core.map.maplibre.MapLibreMapView

/**
 * The map of the app. Screens see markers and a camera only; MapLibre stays inside this module,
 * so the engine can change without touching them. The tile attribution is always visible.
 */
@Composable
fun PrivateTrackerMap(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    camera: MapCamera = MapCamera.FitMarkers,
    selectedId: String? = null,
    interactive: Boolean = true,
    tiles: TileSourceConfig = TileSourceConfig.OpenStreetMap,
    onMarkerClick: (String) -> Unit = {},
    onMapClick: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    Box(modifier) {
        MapLibreMapView(
            markers = markers,
            camera = camera,
            selectedId = selectedId,
            interactive = interactive,
            tiles = tiles,
            onMarkerClick = onMarkerClick,
            onMapClick = onMapClick,
        )
        if (!interactive) {
            // The MapView claims every touch even with gestures off. This layer takes the hit test
            // without consuming anything, so a scrolling parent still gets the drag.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            )
        }
        Text(
            text = tiles.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .clickable { uriHandler.openUri(tiles.attributionUrl) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
