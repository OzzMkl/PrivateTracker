package org.privatetracker.feature.devices.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.accuracy
import org.privatetracker.core.designsystem.text.coordinates
import org.privatetracker.core.designsystem.text.dateTime
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceDetail
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.map.MapCamera
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.PrivateTrackerMap
import org.privatetracker.feature.devices.R
import org.privatetracker.feature.devices.fingerprint
import org.privatetracker.feature.devices.label
import org.privatetracker.feature.devices.markerStyle
import org.privatetracker.feature.devices.tone
import java.time.Instant
import org.privatetracker.core.designsystem.R as DesignR

@Composable
fun DeviceDetailRoute(deviceId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DeviceDetailViewModel, DeviceDetailViewModel.Factory>(
        key = deviceId,
        creationCallback = { factory -> factory.create(deviceId) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeviceDetailScreen(
        state = state,
        onBack = onBack,
        onRename = viewModel::onRename,
        onClearRenameError = viewModel::onClearRenameError,
        onRemove = viewModel::onRemove,
        onDecide = viewModel::onDecide,
    )
}

@Composable
fun DeviceDetailScreen(
    state: DeviceDetailUiState,
    onBack: () -> Unit,
    onRename: (String, () -> Unit) -> Unit,
    onClearRenameError: () -> Unit,
    onRemove: () -> Unit,
    onDecide: (DeviceApproval) -> Unit,
) {
    val detail = state.detail
    // A removed device leaves the screen, whoever removed it.
    LaunchedEffect(state.loading, detail == null) {
        if (!state.loading && detail == null) onBack()
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var revoking by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = detail?.overview?.device?.name ?: stringResource(R.string.device_detail_title),
        onBack = onBack,
        actions = {
            if (detail != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.device_rename)) }, onClick = {
                            menuOpen = false
                            renaming = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.device_remove)) }, onClick = {
                            menuOpen = false
                            removing = true
                        })
                    }
                }
            }
        },
    ) { padding ->
        if (detail == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        DetailContent(
            detail = detail,
            onDecide = { approval ->
                // Cutting off a device that works deserves a second look; letting one in has its own check, the fingerprint.
                if (approval == DeviceApproval.REJECTED && detail.overview.device.approval == DeviceApproval.APPROVED) {
                    revoking = true
                } else {
                    onDecide(approval)
                }
            },
            modifier = Modifier.padding(padding),
        )
        if (revoking) {
            AlertDialog(
                onDismissRequest = { revoking = false },
                title = { Text(stringResource(R.string.device_revoke_title)) },
                text = { Text(stringResource(R.string.device_revoke_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        revoking = false
                        onDecide(DeviceApproval.REJECTED)
                    }) { Text(stringResource(R.string.device_revoke)) }
                },
                dismissButton = { TextButton(onClick = { revoking = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (renaming) {
            RenameDialog(
                current = detail.overview.device.name,
                error = state.renameError,
                onConfirm = { name -> onRename(name) { renaming = false } },
                onDismiss = {
                    onClearRenameError()
                    renaming = false
                },
            )
        }
        if (removing) {
            AlertDialog(
                onDismissRequest = { removing = false },
                title = { Text(stringResource(R.string.device_remove_title)) },
                text = { Text(stringResource(R.string.device_remove_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        removing = false
                        onRemove()
                    }) { Text(stringResource(R.string.device_remove)) }
                },
                dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun DetailContent(detail: DeviceDetail, onDecide: (DeviceApproval) -> Unit, modifier: Modifier) {
    val now = rememberNow()
    val overview = detail.overview
    val device = overview.device
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        overview.lastLocation?.let { location ->
            PrivateTrackerMap(
                markers = listOf(MapMarker(device.id.value, location.latitude, location.longitude, overview.status.markerStyle())),
                camera = MapCamera.Centered(location.latitude, location.longitude, zoom = 16.0),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        AccessCard(device, onDecide)
        SectionCard(title = stringResource(R.string.device_status)) {
            StatusBadge(overview.status.label(), overview.status.tone())
            InfoRow(stringResource(R.string.device_last_seen), relativeTime(device.lastSeenAt, now))
            InfoRow(stringResource(R.string.device_platform), device.platform.name.lowercase().replaceFirstChar { it.uppercase() })
            device.appVersion?.let { InfoRow(stringResource(R.string.device_app_version), it) }
            InfoRow(stringResource(R.string.device_registered), dateTime(device.createdAt))
        }
        LocationCard(overview.lastLocation, now)
        SessionsCard(detail)
    }
}

@Composable
private fun AccessCard(device: Device, onDecide: (DeviceApproval) -> Unit) {
    SectionCard(title = stringResource(R.string.device_access)) {
        if (device.publicKey == null) {
            // Nothing to decide until it comes back with a key; removing it is in the menu.
            StatusBadge(stringResource(DesignR.string.approval_no_key), StatusTone.NEUTRAL)
            Notice(stringResource(R.string.device_no_key))
            return@SectionCard
        }
        StatusBadge(device.approval.label(), device.approval.tone())
        InfoRow(stringResource(DesignR.string.key_fingerprint), device.fingerprint())
        if (device.awaitsApproval) Notice(stringResource(R.string.device_pending_hint), tone = StatusTone.WARNING)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (device.approval) {
                DeviceApproval.PENDING -> {
                    OutlinedButton(onClick = { onDecide(DeviceApproval.REJECTED) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.device_reject))
                    }
                    Button(onClick = { onDecide(DeviceApproval.APPROVED) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.device_approve))
                    }
                }
                DeviceApproval.APPROVED -> OutlinedButton(onClick = { onDecide(DeviceApproval.REJECTED) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_revoke))
                }
                DeviceApproval.REJECTED -> Button(onClick = { onDecide(DeviceApproval.APPROVED) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_approve))
                }
            }
        }
    }
}

@Composable
private fun LocationCard(location: Location?, now: Instant) {
    SectionCard(title = stringResource(R.string.device_last_location)) {
        if (location == null) {
            Text(stringResource(R.string.device_no_location), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        InfoRow(stringResource(R.string.device_coordinates), coordinates(location.latitude, location.longitude))
        InfoRow(stringResource(R.string.device_accuracy), accuracy(location.accuracyM))
        InfoRow(stringResource(R.string.device_recorded), relativeTime(location.recordedAt, now))
        location.receivedAt?.let { InfoRow(stringResource(R.string.device_received), relativeTime(it, now)) }
        location.speedMps?.let { InfoRow(stringResource(R.string.device_speed), stringResource(R.string.device_speed_value, it * 3.6f)) }
        location.altitudeM?.let { InfoRow(stringResource(R.string.device_altitude), stringResource(R.string.device_altitude_value, it)) }
        location.batteryPct?.let { InfoRow(stringResource(R.string.device_battery), stringResource(R.string.device_battery_value, it)) }
        location.provider?.let { InfoRow(stringResource(R.string.device_provider), it) }
        if (location.isMock) Notice(stringResource(R.string.device_mock), tone = StatusTone.WARNING)
    }
}

@Composable
private fun SessionsCard(detail: DeviceDetail) {
    SectionCard(title = stringResource(R.string.device_sessions)) {
        if (detail.recentSessions.isEmpty()) {
            Text(stringResource(R.string.device_no_sessions), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        detail.recentSessions.forEach { session -> SessionRow(session, current = session == detail.currentSession) }
    }
}

@Composable
private fun SessionRow(session: DeviceSession, current: Boolean) {
    val positions = pluralStringResource(R.plurals.device_session_positions, session.locationsReceived, session.locationsReceived)
    Column {
        Text(
            text = if (current) {
                stringResource(R.string.device_session_current, dateTime(session.startedAt), positions)
            } else {
                stringResource(
                    R.string.device_session_ended,
                    dateTime(session.startedAt),
                    dateTime(session.endedAt ?: session.lastActivityAt),
                    positions,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        session.remoteAddress?.let {
            Text(
                stringResource(R.string.device_session_address, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(current: String, error: FieldViolation?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.device_name)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it.message()) } },
                )
                Text(stringResource(R.string.device_rename_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
