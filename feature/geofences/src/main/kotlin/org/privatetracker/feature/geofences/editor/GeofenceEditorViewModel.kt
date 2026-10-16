package org.privatetracker.feature.geofences.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.geo.equivalentRadiusM
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.usecase.server.DeleteGeofence
import org.privatetracker.core.domain.usecase.server.GeofenceDraft
import org.privatetracker.core.domain.usecase.server.GetGeofence
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.SaveGeofence
import kotlin.math.ln

enum class ShapeKind { CIRCLE, POLYGON }

/** What the owner is drawing; kept apart from the devices, which change on their own. */
data class GeofenceForm(
    val kind: ShapeKind = ShapeKind.CIRCLE,
    val center: GeoPoint? = null,
    val radiusM: Double = DEFAULT_RADIUS_M,
    val vertices: List<GeoPoint> = emptyList(),
    val name: String = "",
    val allDevices: Boolean = true,
    val chosen: Set<DeviceId> = emptySet(),
    val notifyOnEnter: Boolean = true,
    val notifyOnExit: Boolean = true,
) {
    val shape: GeofenceShape?
        get() = when (kind) {
            ShapeKind.CIRCLE -> center?.let { GeofenceShape.Circle(it, radiusM) }
            ShapeKind.POLYGON -> GeofenceShape.Polygon(vertices).takeIf { vertices.isNotEmpty() }
        }

    companion object {
        const val DEFAULT_RADIUS_M = 200.0

        fun from(geofence: Geofence) = when (val shape = geofence.shape) {
            is GeofenceShape.Circle -> GeofenceForm(ShapeKind.CIRCLE, center = shape.center, radiusM = shape.radiusM)
            is GeofenceShape.Polygon -> GeofenceForm(ShapeKind.POLYGON, vertices = shape.vertices)
        }.copy(
            name = geofence.name,
            allDevices = geofence.deviceIds == null,
            chosen = geofence.deviceIds.orEmpty(),
            notifyOnEnter = geofence.notifyOnEnter,
            notifyOnExit = geofence.notifyOnExit,
        )
    }
}

/** Where the map opens: on the geofence being edited, or where the devices are. Set once. */
data class EditorCamera(val center: GeoPoint, val zoom: Double)

data class GeofenceEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: GeofenceForm = GeofenceForm(),
    val devices: List<DeviceOverview> = emptyList(),
    val camera: EditorCamera? = null,
    val errors: Map<String, FieldViolation> = emptyMap(),
    val failure: DomainError? = null,
    /** A save is on its way: a second tap must not make a second geofence. */
    val saving: Boolean = false,
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
)

@HiltViewModel(assistedFactory = GeofenceEditorViewModel.Factory::class)
class GeofenceEditorViewModel @AssistedInject constructor(
    /** Empty for a new geofence. */
    @Assisted rawId: String,
    observeDevices: ObserveDeviceOverviews,
    private val getGeofence: GetGeofence,
    private val saveGeofence: SaveGeofence,
    private val deleteGeofence: DeleteGeofence,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawId: String): GeofenceEditorViewModel
    }

    private val id = GeofenceId.parse(rawId)
    private val editor = MutableStateFlow(GeofenceEditorUiState(isNew = id == null))
    private val devices = observeDevices()

    val state: StateFlow<GeofenceEditorUiState> =
        combine(editor, devices) { current, overviews -> current.copy(devices = overviews) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), editor.value)

    init {
        viewModelScope.launch {
            val existing = id?.let { getGeofence(it) }
            val located = devices.first().mapNotNull { it.lastLocation }
            val camera = existing?.let { cameraOn(it.shape) }
                ?: located.takeIf { it.isNotEmpty() }?.let { all ->
                    EditorCamera(GeoPoint(all.map { it.latitude }.average(), all.map { it.longitude }.average()), zoom = 14.0)
                }
                ?: EditorCamera(DEFAULT_CENTER, zoom = 11.0)
            editor.update {
                it.copy(
                    loading = false,
                    form = existing?.let(GeofenceForm::from) ?: it.form,
                    camera = camera,
                    failure = if (id != null && existing == null) DomainError.GeofenceNotFound else null,
                )
            }
        }
    }

    fun onKind(kind: ShapeKind) = change { it.copy(kind = kind) }

    /** A tap on the map: the circle's center, or the polygon's next vertex. */
    fun onMapTap(point: GeoPoint) = change { form ->
        when (form.kind) {
            ShapeKind.CIRCLE -> form.copy(center = point)
            ShapeKind.POLYGON -> if (form.vertices.size < Geofence.MAX_VERTICES) form.copy(vertices = form.vertices + point) else form
        }
    }

    fun onRadius(meters: Double) = change { it.copy(radiusM = meters) }
    fun onUndoVertex() = change { it.copy(vertices = it.vertices.dropLast(1)) }
    fun onClearShape() = change { it.copy(center = null, vertices = emptyList()) }
    fun onName(name: String) = change { it.copy(name = name) }
    fun onAllDevices(all: Boolean) = change { it.copy(allDevices = all) }
    fun onDevice(device: DeviceId, chosen: Boolean) = change { it.copy(chosen = if (chosen) it.chosen + device else it.chosen - device) }
    fun onNotifyEnter(on: Boolean) = change { it.copy(notifyOnEnter = on) }
    fun onNotifyExit(on: Boolean) = change { it.copy(notifyOnExit = on) }

    fun onSave() {
        if (editor.value.saving || editor.value.done) return
        val form = editor.value.form
        val shape = form.shape
        if (shape == null) {
            val field = if (form.kind == ShapeKind.CIRCLE) "center" else "vertices"
            editor.update { it.copy(errors = mapOf(field to FieldViolation(field, FieldViolation.REQUIRED))) }
            return
        }
        val draft = GeofenceDraft(
            id = id,
            name = form.name,
            shape = shape,
            deviceIds = if (form.allDevices) null else form.chosen,
            notifyOnEnter = form.notifyOnEnter,
            notifyOnExit = form.notifyOnExit,
        )
        editor.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = saveGeofence(draft)) {
                is Outcome.Success -> editor.update { it.copy(done = true) }
                is Outcome.Failure -> editor.update { state ->
                    val error = result.error
                    if (error is DomainError.Validation) {
                        state.copy(saving = false, errors = error.violations.associateBy { it.field })
                    } else {
                        state.copy(saving = false, failure = error)
                    }
                }
            }
        }
    }

    fun onDelete() {
        val existing = id ?: return
        viewModelScope.launch {
            deleteGeofence(existing)
            editor.update { it.copy(done = true) }
        }
    }

    private fun change(transform: (GeofenceForm) -> GeofenceForm) {
        editor.update { it.copy(form = transform(it.form), errors = emptyMap(), failure = null) }
    }

    private fun cameraOn(shape: GeofenceShape): EditorCamera {
        val center = when (shape) {
            is GeofenceShape.Circle -> shape.center
            is GeofenceShape.Polygon -> GeoPoint(shape.vertices.map { it.latitude }.average(), shape.vertices.map { it.longitude }.average())
        }
        // About 16 at a 150 m radius, one level less each time the size doubles.
        val zoom = (16.0 - ln(equivalentRadiusM(shape) / 150.0) / ln(2.0)).coerceIn(3.0, 18.0)
        return EditorCamera(center, zoom)
    }

    private companion object {
        /** Mexico City's center, where the project started, when there is nothing else to show. */
        val DEFAULT_CENTER = GeoPoint(19.4326, -99.1332)
    }
}
