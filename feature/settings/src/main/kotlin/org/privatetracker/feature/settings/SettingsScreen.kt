package org.privatetracker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.LocationPriority
import kotlin.math.abs

@Composable
fun SettingsRoute(onOpenPermissions: () -> Unit, onOpenPairing: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            onModeChange = viewModel::onModeChange,
            onTrackerChange = viewModel::onTrackerChange,
            onServerChange = viewModel::onServerChange,
            onSaveTracker = viewModel::onSaveTracker,
            onSaveServer = viewModel::onSaveServer,
            onTestConnection = viewModel::onTestConnection,
            onRestartServer = viewModel::onRestartServer,
            onSavedShown = viewModel::onSavedShown,
            onOpenPermissions = onOpenPermissions,
            onOpenPairing = onOpenPairing,
        ),
    )
}

class SettingsActions(
    val onModeChange: (AppMode) -> Unit,
    val onTrackerChange: ((TrackerForm) -> TrackerForm) -> Unit,
    val onServerChange: ((ServerForm) -> ServerForm) -> Unit,
    val onSaveTracker: () -> Unit,
    val onSaveServer: () -> Unit,
    val onTestConnection: () -> Unit,
    val onRestartServer: () -> Unit,
    val onSavedShown: () -> Unit,
    val onOpenPermissions: () -> Unit,
    val onOpenPairing: () -> Unit,
)

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.settings_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            actions.onSavedShown()
            snackbar.showSnackbar(savedText)
        }
    }
    ScreenScaffold(title = stringResource(R.string.settings_title), snackbarHostState = snackbar) { padding ->
        val mode = state.mode
        if (mode == null || state.tracker == null || state.server == null) {
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
            ModeCard(mode, actions.onModeChange)
            SectionCard(title = stringResource(R.string.settings_permissions)) {
                Text(stringResource(R.string.settings_permissions_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.onOpenPermissions, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_permissions_open))
                }
            }
            if (mode.tracks) TrackerSection(state.tracker, state.connectionTest, actions)
            if (mode.serves) ServerSection(state.server, state.serverRestartNeeded, state.restarting, actions)
            SectionCard(title = stringResource(R.string.settings_about)) {
                InfoRow(stringResource(R.string.about_version), state.appVersion)
                InfoRow(stringResource(R.string.about_protocol), "v${state.protocolVersion}")
                InfoRow(stringResource(R.string.about_map_data), "© OpenStreetMap")
            }
        }
    }
}

@Composable
private fun ModeCard(mode: AppMode, onModeChange: (AppMode) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.settings_mode)) {
        Text(stringResource(mode.titleRes()), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(mode.bodyRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.settings_mode_change))
        }
    }
    if (choosing) {
        var selected by rememberSaveable { mutableStateOf(mode) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_mode_change)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(R.string.settings_mode_change_body), style = MaterialTheme.typography.bodyMedium)
                    AppMode.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == selected, role = Role.RadioButton, onClick = { selected = option })
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(stringResource(option.titleRes()), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    if (selected != mode) onModeChange(selected)
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerSection(form: TrackerForm, test: ConnectionTest, actions: SettingsActions) {
    val change = actions.onTrackerChange
    SectionCard(title = stringResource(R.string.settings_tracker)) {
        Button(onClick = actions.onOpenPairing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tracker_pair_qr)) }
        Text(
            stringResource(R.string.tracker_pair_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormField(
            value = form.serverUrl,
            onValueChange = { value -> change { it.copy(serverUrl = value) } },
            label = stringResource(R.string.tracker_server_url),
            placeholder = stringResource(R.string.tracker_server_url_hint),
            error = form.errors["serverUrl"],
            keyboardType = KeyboardType.Uri,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = actions.onTestConnection, enabled = test != ConnectionTest.Running) {
                Text(stringResource(if (test == ConnectionTest.Running) R.string.tracker_testing else R.string.tracker_test))
            }
            if (test == ConnectionTest.Running) CircularProgressIndicator(Modifier.padding(start = 12.dp))
        }
        ConnectionTestResult(test)
        FormField(
            value = form.deviceName,
            onValueChange = { value -> change { it.copy(deviceName = value) } },
            label = stringResource(R.string.settings_tracker_device_name),
            error = form.errors["deviceName"],
        )
        FormField(
            value = form.intervalSeconds,
            onValueChange = { value -> change { it.copy(intervalSeconds = value) } },
            label = stringResource(R.string.settings_tracker_interval),
            error = form.errors["intervalSeconds"],
            keyboardType = KeyboardType.Number,
        )
        FormField(
            value = form.minDistanceM,
            onValueChange = { value -> change { it.copy(minDistanceM = value) } },
            label = stringResource(R.string.tracker_min_distance),
            error = form.errors["minDistanceM"],
            keyboardType = KeyboardType.Decimal,
        )
        FormField(
            value = form.maxAccuracyM,
            onValueChange = { value -> change { it.copy(maxAccuracyM = value) } },
            label = stringResource(R.string.tracker_max_accuracy),
            error = form.errors["maxAccuracyM"],
            keyboardType = KeyboardType.Decimal,
        )
        Text(stringResource(R.string.tracker_priority), style = MaterialTheme.typography.labelLarge)
        val priorities = listOf(
            LocationPriority.HIGH_ACCURACY to R.string.priority_high,
            LocationPriority.BALANCED to R.string.priority_balanced,
            LocationPriority.LOW_POWER to R.string.priority_low,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            priorities.forEachIndexed { index, (priority, label) ->
                SegmentedButton(
                    selected = form.priority == priority,
                    onClick = { change { it.copy(priority = priority) } },
                    shape = SegmentedButtonDefaults.itemShape(index, priorities.size),
                ) { Text(stringResource(label)) }
            }
        }
        SwitchRow(
            title = stringResource(R.string.tracker_start_on_boot),
            body = stringResource(R.string.tracker_start_on_boot_body),
            checked = form.startOnBoot,
            onCheckedChange = { value -> change { it.copy(startOnBoot = value) } },
        )
        FormField(
            value = form.batchSize,
            onValueChange = { value -> change { it.copy(batchSize = value) } },
            label = stringResource(R.string.tracker_batch_size),
            error = form.errors["batchSize"],
            keyboardType = KeyboardType.Number,
        )
        FormField(
            value = form.maxQueueSize,
            onValueChange = { value -> change { it.copy(maxQueueSize = value) } },
            label = stringResource(R.string.tracker_max_queue),
            error = form.errors["maxQueueSize"],
            keyboardType = KeyboardType.Number,
        )
        Button(onClick = actions.onSaveTracker, enabled = form.dirty, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun ConnectionTestResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle, ConnectionTest.Running -> Unit
        is ConnectionTest.Passed -> {
            val check = test.check
            if (check.compatible) {
                Notice(
                    stringResource(R.string.tracker_test_ok, check.server.name, check.latency.toMillis().toInt()),
                    tone = StatusTone.POSITIVE,
                )
            } else {
                Notice(stringResource(R.string.tracker_test_incompatible, check.server.name, check.server.protocolVersion), tone = StatusTone.NEGATIVE)
            }
            val offset = check.clockOffset.seconds
            if (abs(offset) >= CLOCK_WARNING_SECONDS) Notice(stringResource(R.string.tracker_test_clock, offset.toInt()))
        }
        is ConnectionTest.Failed -> {
            Notice(test.error.message(), tone = StatusTone.NEGATIVE)
            if (test.suggestLocalNetwork) Notice(stringResource(R.string.tracker_test_local_network))
        }
    }
}

@Composable
private fun ServerSection(form: ServerForm, restartNeeded: Boolean, restarting: Boolean, actions: SettingsActions) {
    val change = actions.onServerChange
    SectionCard(title = stringResource(R.string.settings_server)) {
        FormField(
            value = form.serverName,
            onValueChange = { value -> change { it.copy(serverName = value) } },
            label = stringResource(R.string.settings_server_name),
            error = form.errors["serverName"],
        )
        FormField(
            value = form.port,
            onValueChange = { value -> change { it.copy(port = value) } },
            label = stringResource(R.string.server_port),
            error = form.errors["port"],
            keyboardType = KeyboardType.Number,
        )
        FormField(
            value = form.bindAddress,
            onValueChange = { value -> change { it.copy(bindAddress = value) } },
            label = stringResource(R.string.server_bind_address),
            error = form.errors["bindAddress"],
            keyboardType = KeyboardType.Uri,
        )
        SwitchRow(
            title = stringResource(R.string.server_auto_start),
            checked = form.autoStart,
            onCheckedChange = { value -> change { it.copy(autoStart = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.server_accept_new),
            body = stringResource(R.string.server_accept_new_body),
            checked = form.acceptNewDevices,
            onCheckedChange = { value -> change { it.copy(acceptNewDevices = value) } },
        )
        FormField(
            value = form.onlineThresholdSeconds,
            onValueChange = { value -> change { it.copy(onlineThresholdSeconds = value) } },
            label = stringResource(R.string.server_online_threshold),
            error = form.errors["onlineThresholdSeconds"],
            keyboardType = KeyboardType.Number,
        )
        FormField(
            value = form.retentionDays,
            onValueChange = { value -> change { it.copy(retentionDays = value) } },
            label = stringResource(R.string.server_retention),
            error = form.errors["retentionDays"],
            keyboardType = KeyboardType.Number,
        )
        FormField(
            value = form.maxBatchSize,
            onValueChange = { value -> change { it.copy(maxBatchSize = value) } },
            label = stringResource(R.string.server_max_batch),
            error = form.errors["maxBatchSize"],
            keyboardType = KeyboardType.Number,
        )
        SwitchRow(
            title = stringResource(R.string.server_advertise),
            body = stringResource(R.string.server_advertise_body),
            checked = form.advertiseOnLan,
            onCheckedChange = { value -> change { it.copy(advertiseOnLan = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.server_expose_read),
            body = stringResource(R.string.server_expose_read_body),
            checked = form.exposeReadApi,
            onCheckedChange = { value -> change { it.copy(exposeReadApi = value) } },
        )
        if (restartNeeded) {
            Notice(
                text = stringResource(R.string.server_restart_needed),
                actionLabel = if (restarting) null else stringResource(R.string.server_restart),
                onAction = actions.onRestartServer,
            )
        }
        Button(onClick = actions.onSaveServer, enabled = form.dirty, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: FieldViolation?,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it.message()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, body: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = Role.Switch, onClick = { onCheckedChange(!checked) }),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun AppMode.titleRes(): Int = when (this) {
    AppMode.TRACKER -> R.string.mode_tracker
    AppMode.SERVER -> R.string.mode_server
    AppMode.TRACKER_AND_SERVER -> R.string.mode_both
}

private fun AppMode.bodyRes(): Int = when (this) {
    AppMode.TRACKER -> R.string.mode_tracker_body
    AppMode.SERVER -> R.string.mode_server_body
    AppMode.TRACKER_AND_SERVER -> R.string.mode_both_body
}

/** Clocks further apart than this make devices look offline or locations look old. */
private const val CLOCK_WARNING_SECONDS = 60L
