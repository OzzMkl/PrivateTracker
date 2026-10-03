package org.privatetracker.feature.devices.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.accuracy
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.PrivateTrackerMap
import org.privatetracker.feature.devices.R
import org.privatetracker.feature.devices.label
import org.privatetracker.feature.devices.markerStyle
import org.privatetracker.feature.devices.tone

@Composable
fun DevicesMapRoute(onOpenDevice: (DeviceId) -> Unit, viewModel: DevicesMapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DevicesMapScreen(state, onSelect = viewModel::onSelect, onOpenDevice = onOpenDevice)
}

@Composable
fun DevicesMapScreen(state: DevicesMapUiState, onSelect: (String?) -> Unit, onOpenDevice: (DeviceId) -> Unit) {
    ScreenScaffold(title = stringResource(R.string.devices_map_title)) { padding ->
        val located = state.located
        if (located == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrivateTrackerMap(
                markers = located.map { overview ->
                    val location = checkNotNull(overview.lastLocation)
                    MapMarker(overview.device.id.value, location.latitude, location.longitude, overview.status.markerStyle())
                },
                selectedId = state.selected?.device?.id?.value,
                onMarkerClick = onSelect,
                onMapClick = { onSelect(null) },
                modifier = Modifier.fillMaxSize(),
            )
            if (located.isEmpty()) {
                Notice(
                    text = stringResource(R.string.devices_map_empty),
                    tone = StatusTone.NEUTRAL,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )
            }
            state.selected?.let { selected ->
                SelectedDeviceCard(
                    overview = selected,
                    onOpen = { onOpenDevice(selected.device.id) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectedDeviceCard(overview: DeviceOverview, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(overview.device.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(overview.status.label(), overview.status.tone())
            }
            Text(
                listOf(
                    stringResource(R.string.devices_seen, relativeTime(overview.device.lastSeenAt, now)),
                    accuracy(overview.lastLocation?.accuracyM),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.devices_open_detail)) }
        }
    }
}
