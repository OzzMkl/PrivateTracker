package org.privatetracker.feature.server.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.ServerAddress
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.ServerStatus
import org.privatetracker.feature.server.R

@Composable
fun ServerRoute(
    onOpenPermissions: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenPairing: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerScreen(
        state,
        onStart = viewModel::onStart,
        onStop = viewModel::onStop,
        onOpenPermissions = onOpenPermissions,
        onOpenDevices = onOpenDevices,
        onOpenPairing = onOpenPairing,
    )
}

@Composable
fun ServerScreen(
    state: ServerUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    ScreenScaffold(title = stringResource(R.string.server_title), snackbarHostState = snackbar) { padding ->
        val status = state.status
        if (status == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status, state.deviceCount, state.serverKeyFingerprint, onStart, onStop, onOpenPairing)
            if (state.pendingCount > 0) {
                Notice(
                    text = pluralStringResource(R.plurals.server_pending, state.pendingCount, state.pendingCount),
                    tone = StatusTone.WARNING,
                    actionLabel = stringResource(R.string.server_review),
                    onAction = onOpenDevices,
                )
            }
            state.startError?.let { error ->
                Notice(
                    text = error.message(),
                    tone = StatusTone.NEGATIVE,
                    actionLabel = if (error is DomainError.Permission) stringResource(R.string.server_open_permissions) else null,
                    onAction = onOpenPermissions,
                )
            }
            (status.state as? ServerRunState.Failed)?.let { Notice(it.error.message(), tone = StatusTone.NEGATIVE) }
            if (status.state is ServerRunState.Running) AddressesCard(status.addresses, snackbar)
            Notice(stringResource(R.string.server_security_notice), tone = StatusTone.NEUTRAL)
            Notice(stringResource(R.string.server_power_hint), tone = StatusTone.NEUTRAL)
        }
    }
}

@Composable
private fun StatusCard(
    status: ServerStatus,
    deviceCount: Int,
    keyFingerprint: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.server_status)) {
        when (val state = status.state) {
            ServerRunState.Stopped -> StatusBadge(stringResource(R.string.server_stopped), StatusTone.NEUTRAL)
            ServerRunState.Starting -> StatusBadge(stringResource(R.string.server_starting), StatusTone.WARNING)
            ServerRunState.Stopping -> StatusBadge(stringResource(R.string.server_stopping), StatusTone.WARNING)
            is ServerRunState.Running -> StatusBadge(stringResource(R.string.server_running, state.port), StatusTone.POSITIVE)
            is ServerRunState.Failed -> StatusBadge(stringResource(R.string.server_failed), StatusTone.NEGATIVE)
        }
        InfoRow(stringResource(R.string.server_name), status.config.serverName)
        InfoRow(stringResource(R.string.server_requests), status.activity.requestsServed.toString())
        InfoRow(stringResource(R.string.server_devices), deviceCount.toString())
        InfoRow(
            stringResource(R.string.server_new_devices),
            stringResource(if (status.config.acceptNewDevices) R.string.server_yes else R.string.server_no),
        )
        InfoRow(stringResource(R.string.server_key_fingerprint), keyFingerprint ?: "…")
        if (status.state is ServerRunState.Running && status.addresses.isNotEmpty()) {
            Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_pair)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status.state) {
                is ServerRunState.Running, ServerRunState.Starting, ServerRunState.Stopping ->
                    OutlinedButton(onClick = onStop, enabled = status.state != ServerRunState.Stopping, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.server_stop))
                    }
                ServerRunState.Stopped, is ServerRunState.Failed ->
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_start)) }
            }
        }
    }
}

@Composable
private fun AddressesCard(addresses: List<ServerAddress>, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.server_copied)
    SectionCard(title = stringResource(R.string.server_addresses)) {
        if (addresses.isEmpty()) {
            Text(stringResource(R.string.server_addresses_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(
            stringResource(R.string.server_addresses_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        addresses.forEach { address ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SelectionContainer { Text(address.url, style = MaterialTheme.typography.bodyLarge) }
                    Text(
                        address.kind.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(address.url, address.url)))
                            snackbar.showSnackbar(copied)
                        }
                    },
                ) { Text(stringResource(R.string.server_copy)) }
            }
        }
    }
}

@Composable
private fun AddressKind.label(): String = stringResource(
    when (this) {
        AddressKind.LAN -> R.string.server_kind_lan
        AddressKind.VPN -> R.string.server_kind_vpn
        AddressKind.OTHER -> R.string.server_kind_other
        AddressKind.LOOPBACK -> R.string.server_kind_loopback
    },
)
