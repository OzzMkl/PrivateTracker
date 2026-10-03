package org.privatetracker.feature.devices.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.EmptyState
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.feature.devices.R
import org.privatetracker.feature.devices.fingerprint
import org.privatetracker.feature.devices.label
import org.privatetracker.feature.devices.tone
import java.time.Instant
import org.privatetracker.core.designsystem.R as DesignR

@Composable
fun DeviceListRoute(onOpenDevice: (DeviceId) -> Unit, viewModel: DeviceListViewModel = hiltViewModel()) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    DeviceListScreen(devices, onOpenDevice, onDecide = viewModel::onDecide)
}

@Composable
fun DeviceListScreen(
    devices: List<DeviceOverview>?,
    onOpenDevice: (DeviceId) -> Unit,
    onDecide: (DeviceId, DeviceApproval) -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.devices_title)) { padding ->
        when {
            devices == null -> LoadingBox(Modifier.padding(padding))
            devices.isEmpty() -> EmptyState(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.devices_empty_title),
                body = stringResource(R.string.devices_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> DeviceList(devices, padding, onOpenDevice, onDecide)
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<DeviceOverview>,
    padding: PaddingValues,
    onOpenDevice: (DeviceId) -> Unit,
    onDecide: (DeviceId, DeviceApproval) -> Unit,
) {
    val now = rememberNow()
    val (pending, others) = devices.partition { it.device.awaitsApproval }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (pending.isNotEmpty()) {
            item(key = "pending-header") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.devices_pending_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.devices_pending_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pending, key = { it.device.id.value }) { overview ->
                PendingDevice(overview, now, onOpenDevice, onDecide)
                HorizontalDivider()
            }
        }
        items(others, key = { it.device.id.value }) { overview ->
            val battery = overview.lastLocation?.batteryPct
            ListItem(
                headlineContent = { Text(overview.device.name) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            stringResource(R.string.devices_seen, relativeTime(overview.device.lastSeenAt, now)),
                            battery?.let { stringResource(R.string.devices_battery, it) },
                        ).joinToString(" · "),
                    )
                },
                trailingContent = {
                    when {
                        overview.device.publicKey == null -> StatusBadge(stringResource(DesignR.string.approval_no_key), StatusTone.NEUTRAL)
                        overview.device.approval == DeviceApproval.REJECTED ->
                            StatusBadge(overview.device.approval.label(), overview.device.approval.tone())
                        else -> StatusBadge(overview.status.label(), overview.status.tone())
                    }
                },
                modifier = Modifier.clickable { onOpenDevice(overview.device.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PendingDevice(
    overview: DeviceOverview,
    now: Instant,
    onOpenDevice: (DeviceId) -> Unit,
    onDecide: (DeviceId, DeviceApproval) -> Unit,
) {
    val device = overview.device
    Column(Modifier.clickable { onOpenDevice(device.id) }) {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.devices_fingerprint, device.fingerprint()), fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.devices_requested, relativeTime(device.lastSeenAt, now)))
                }
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onDecide(device.id, DeviceApproval.REJECTED) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.device_reject))
            }
            Button(onClick = { onDecide(device.id, DeviceApproval.APPROVED) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.device_approve))
            }
        }
    }
}
