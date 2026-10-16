package org.privatetracker.feature.geofences.list

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.feature.geofences.R

@Composable
fun GeofencesRoute(onBack: () -> Unit, onOpen: (GeofenceId?) -> Unit, viewModel: GeofencesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val context = LocalContext.current
    // Asking again after a denial shows no dialog: system settings open instead.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    GeofencesScreen(
        state,
        onBack = onBack,
        onOpen = onOpen,
        onUnmute = {
            if (!asked && context.lacksNotificationPermission()) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.openNotificationSettings()
            }
        },
    )
}

@Composable
fun GeofencesScreen(state: GeofencesUiState, onBack: () -> Unit, onOpen: (GeofenceId?) -> Unit, onUnmute: () -> Unit) {
    ScreenScaffold(
        title = stringResource(R.string.geofence_list_title),
        onBack = onBack,
        actions = { IconButton(onClick = { onOpen(null) }) { Icon(Icons.Filled.Add, stringResource(R.string.geofence_new)) } },
    ) { padding ->
        val geofences = state.geofences
        if (geofences == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val now = rememberNow()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.muted) {
                item {
                    Notice(
                        stringResource(R.string.geofence_muted),
                        actionLabel = stringResource(R.string.geofence_unmute),
                        onAction = onUnmute,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.geofence_section_areas)) }
            if (geofences.isEmpty()) {
                item { Notice(stringResource(R.string.geofence_none), tone = StatusTone.NEUTRAL, modifier = Modifier.padding(16.dp)) }
            }
            items(geofences, key = { it.id.value }) { geofence ->
                GeofenceRow(geofence, onClick = { onOpen(geofence.id) })
                HorizontalDivider()
            }
            item { SectionTitle(stringResource(R.string.geofence_section_events)) }
            if (state.events.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.geofence_no_events),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.events) { event -> EventRow(event, now) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun GeofenceRow(geofence: Geofence, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(geofence.name, style = MaterialTheme.typography.bodyLarge)
        val shape = when (val shape = geofence.shape) {
            is GeofenceShape.Circle -> stringResource(R.string.geofence_circle_summary, distance(shape.radiusM))
            is GeofenceShape.Polygon -> pluralStringResource(R.plurals.geofence_polygon_summary, shape.vertices.size, shape.vertices.size)
        }
        val devices = geofence.deviceIds?.let { pluralStringResource(R.plurals.geofence_some_devices, it.size, it.size) }
            ?: stringResource(R.string.geofence_all_devices)
        Text(
            listOf(shape, devices, notifies(geofence)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(view: GeofenceEventView, now: java.time.Instant) {
    val event = view.event
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                if (event.transition == GeofenceTransition.ENTER) R.string.geofence_event_enter else R.string.geofence_event_exit,
                view.deviceName,
                view.geofenceName,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            listOfNotNull(relativeTime(event.occurredAt, now), stringResource(R.string.geofence_event_silent).takeIf { !event.alerted })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun notifies(geofence: Geofence): String = stringResource(
    when {
        geofence.notifyOnEnter && geofence.notifyOnExit -> R.string.geofence_notifies_both
        geofence.notifyOnEnter -> R.string.geofence_notifies_enter
        geofence.notifyOnExit -> R.string.geofence_notifies_exit
        else -> R.string.geofence_notifies_none
    },
)

@Composable
internal fun distance(meters: Double): String =
    if (meters >= 1_000) stringResource(R.string.geofence_km, meters / 1_000) else stringResource(R.string.geofence_meters, meters)

private fun Context.lacksNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** The app's notification settings, where its channels can be turned back on too. */
private fun Context.openNotificationSettings() {
    try {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    } catch (_: ActivityNotFoundException) {
        // Some vendor builds lack the screen; the owner can still find it by hand.
    }
}
