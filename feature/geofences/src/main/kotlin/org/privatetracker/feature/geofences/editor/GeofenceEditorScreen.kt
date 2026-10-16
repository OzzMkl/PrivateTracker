package org.privatetracker.feature.geofences.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.domain.geo.outline
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.map.MapArea
import org.privatetracker.core.map.MapCamera
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.MapPoint
import org.privatetracker.core.map.MarkerStyle
import org.privatetracker.core.map.PrivateTrackerMap
import org.privatetracker.feature.geofences.R
import org.privatetracker.feature.geofences.list.distance
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun GeofenceEditorRoute(geofenceId: String?, onDone: () -> Unit) {
    val viewModel = hiltViewModel<GeofenceEditorViewModel, GeofenceEditorViewModel.Factory>(
        key = "geofence-${geofenceId.orEmpty()}",
        creationCallback = { factory -> factory.create(geofenceId.orEmpty()) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    GeofenceEditorScreen(state, viewModel, onBack = onDone)
}

@Composable
private fun GeofenceEditorScreen(state: GeofenceEditorUiState, actions: GeofenceEditorViewModel, onBack: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = stringResource(if (state.isNew) R.string.geofence_create_title else R.string.geofence_edit_title),
        onBack = onBack,
        actions = {
            if (!state.isNew) TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.geofence_delete)) }
        },
    ) { padding ->
        val camera = state.camera
        if (state.loading || camera == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val form = state.form
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().height(MAP_HEIGHT)) {
                PrivateTrackerMap(
                    markers = shapeMarkers(form) + state.devices.mapNotNull { overview ->
                        overview.lastLocation?.let { MapMarker(overview.device.id.value, it.latitude, it.longitude, MarkerStyle.OFFLINE) }
                    },
                    areas = form.shape?.let { shape -> listOf(MapArea("draft", outline(shape).map { MapPoint(it.latitude, it.longitude) }, emphasized = true)) }
                        .orEmpty(),
                    camera = MapCamera.Centered(camera.center.latitude, camera.center.longitude, camera.zoom),
                    onMapTap = { actions.onMapTap(GeoPoint(it.latitude, it.longitude)) },
                    markersClickable = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.failure?.let { Notice(it.message(), tone = StatusTone.NEGATIVE) }
                ShapeControls(form, state.errors, actions)
                OutlinedTextField(
                    value = form.name,
                    onValueChange = actions::onName,
                    label = { Text(stringResource(R.string.geofence_name)) },
                    singleLine = true,
                    isError = "name" in state.errors,
                    supportingText = state.errors["name"]?.let { { Text(it.message()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                DeviceChoice(state, actions)
                SwitchLine(stringResource(R.string.geofence_notify_enter), form.notifyOnEnter, actions::onNotifyEnter)
                SwitchLine(stringResource(R.string.geofence_notify_exit), form.notifyOnExit, actions::onNotifyExit)
                Button(onClick = actions::onSave, enabled = !state.saving, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.geofence_save))
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.geofence_delete_title)) },
            text = { Text(stringResource(R.string.geofence_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    actions.onDelete()
                }) { Text(stringResource(R.string.geofence_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.geofence_cancel)) } },
        )
    }
}

@Composable
private fun ShapeControls(form: GeofenceForm, errors: Map<String, FieldViolation>, actions: GeofenceEditorViewModel) {
    val kinds = listOf(ShapeKind.CIRCLE to R.string.geofence_circle, ShapeKind.POLYGON to R.string.geofence_polygon)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, (kind, label) ->
            SegmentedButton(
                selected = form.kind == kind,
                onClick = { actions.onKind(kind) },
                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
            ) { Text(stringResource(label)) }
        }
    }
    Text(
        stringResource(if (form.kind == ShapeKind.CIRCLE) R.string.geofence_circle_hint else R.string.geofence_polygon_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (form.kind) {
        ShapeKind.CIRCLE -> {
            Text(stringResource(R.string.geofence_radius, distance(form.radiusM)), style = MaterialTheme.typography.bodyMedium)
            // Logarithmic: as easy to pick 80 m as 3 km.
            Slider(
                value = (ln(form.radiusM / MIN_SLIDER_M) / ln(MAX_SLIDER_M / MIN_SLIDER_M)).toFloat().coerceIn(0f, 1f),
                onValueChange = { actions.onRadius(roundRadius(MIN_SLIDER_M * (MAX_SLIDER_M / MIN_SLIDER_M).pow(it.toDouble()))) },
            )
        }
        ShapeKind.POLYGON -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::onUndoVertex, enabled = form.vertices.isNotEmpty()) { Text(stringResource(R.string.geofence_undo)) }
            OutlinedButton(onClick = actions::onClearShape, enabled = form.vertices.isNotEmpty()) { Text(stringResource(R.string.geofence_clear)) }
        }
    }
    shapeError(errors)?.let { Notice(it, tone = StatusTone.NEGATIVE) }
}

@Composable
private fun shapeError(errors: Map<String, FieldViolation>): String? = when {
    "center" in errors -> stringResource(R.string.geofence_error_center)
    "radius" in errors -> stringResource(R.string.geofence_error_radius)
    errors["vertices"]?.rule == FieldViolation.INVALID_FORMAT -> stringResource(R.string.geofence_error_vertices_shape)
    "vertices" in errors -> stringResource(R.string.geofence_error_vertices_count)
    else -> null
}

@Composable
private fun DeviceChoice(state: GeofenceEditorUiState, actions: GeofenceEditorViewModel) {
    val form = state.form
    Text(stringResource(R.string.geofence_devices), style = MaterialTheme.typography.titleSmall)
    SwitchLine(stringResource(R.string.geofence_all_devices), form.allDevices, actions::onAllDevices)
    if (!form.allDevices) {
        state.devices.forEach { overview ->
            val id: DeviceId = overview.device.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = id in form.chosen, onCheckedChange = { actions.onDevice(id, it) })
                Text(overview.device.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if ("devices" in state.errors) Notice(stringResource(R.string.geofence_error_devices), tone = StatusTone.NEGATIVE)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The circle's center, or every vertex in order. */
private fun shapeMarkers(form: GeofenceForm): List<MapMarker> = when (form.kind) {
    ShapeKind.CIRCLE -> listOfNotNull(form.center?.let { MapMarker("center", it.latitude, it.longitude, MarkerStyle.VERTEX) })
    ShapeKind.POLYGON -> form.vertices.mapIndexed { i, v -> MapMarker("vertex-$i", v.latitude, v.longitude, MarkerStyle.VERTEX) }
}

/** 10 m steps below 1 km, 100 m above: readable values. */
private fun roundRadius(meters: Double): Double = if (meters < 1_000) (meters / 10).toInt() * 10.0 else (meters / 100).toInt() * 100.0

private val MAP_HEIGHT = 300.dp
private const val MIN_SLIDER_M = 50.0
private const val MAX_SLIDER_M = 5_000.0
