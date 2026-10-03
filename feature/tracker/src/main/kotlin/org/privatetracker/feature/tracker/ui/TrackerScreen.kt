package org.privatetracker.feature.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.common.result.DomainError
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
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.SkipReason
import org.privatetracker.core.domain.model.TrackingStatus
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.map.MapCamera
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.MarkerStyle
import org.privatetracker.core.map.PrivateTrackerMap
import org.privatetracker.feature.tracker.R
import java.time.Instant
import org.privatetracker.core.designsystem.R as DesignR

@Composable
fun TrackerRoute(
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPairing: () -> Unit,
    viewModel: TrackerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrackerScreen(
        state = state,
        onStart = viewModel::onStart,
        onStop = viewModel::onStop,
        onOpenSettings = onOpenSettings,
        onOpenPermissions = onOpenPermissions,
        onOpenPairing = onOpenPairing,
    )
}

@Composable
fun TrackerScreen(
    state: TrackerUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.tracker_title)) { padding ->
        val status = state.status
        if (status == null) {
            LoadingBox(Modifier.padding(padding))
            return@ScreenScaffold
        }
        val now = rememberNow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status, state.keyFingerprint, state.busy, onStart, onStop)
            if ((status.activity.lastUpload as? UploadResult.RetryLater)?.error == DomainError.DevicePendingApproval) {
                Notice(
                    text = stringResource(R.string.tracker_waiting_approval, state.keyFingerprint ?: "…"),
                    tone = StatusTone.WARNING,
                )
            }
            if (status.config.serverUrl.isBlank()) {
                Notice(
                    text = stringResource(R.string.tracker_not_configured),
                    actionLabel = stringResource(R.string.tracker_scan_qr),
                    onAction = onOpenPairing,
                )
            }
            listOfNotNull(state.startError, status.activity.error).distinct().forEach { error ->
                ErrorNotice(error, onOpenSettings, onOpenPermissions)
            }
            if (status.running || status.activity.lastFix != null) LastLocationCard(status, now)
            QueueCard(status, now)
        }
    }
}

@Composable
private fun StatusCard(status: TrackingStatus, keyFingerprint: String?, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    SectionCard(title = stringResource(R.string.tracker_status)) {
        when {
            status.running -> StatusBadge(stringResource(R.string.tracker_running), StatusTone.POSITIVE)
            status.enabled -> StatusBadge(stringResource(R.string.tracker_enabled_not_running), StatusTone.WARNING)
            else -> StatusBadge(stringResource(R.string.tracker_stopped), StatusTone.NEUTRAL)
        }
        InfoRow(stringResource(R.string.tracker_server), status.config.serverUrl.ifBlank { "—" })
        // Pinned when paired by QR: the same fingerprint the server screen shows.
        status.config.serverKey.takeIf { it.isNotBlank() }?.let(::keyFingerprint)?.let {
            InfoRow(stringResource(R.string.tracker_server_fingerprint), it)
        }
        InfoRow(stringResource(R.string.tracker_device_name), status.config.deviceName.ifBlank { "—" })
        InfoRow(stringResource(R.string.tracker_interval), stringResource(R.string.tracker_interval_value, status.config.intervalSeconds))
        InfoRow(stringResource(DesignR.string.key_fingerprint), keyFingerprint ?: "…")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!status.running) {
                Button(onClick = onStart, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tracker_start)) }
            }
            if (status.enabled || status.running) {
                OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tracker_stop)) }
            }
        }
    }
}

@Composable
private fun ErrorNotice(error: DomainError, onOpenSettings: () -> Unit, onOpenPermissions: () -> Unit) {
    val (label, action) = when (error) {
        is DomainError.Permission -> stringResource(R.string.tracker_open_permissions) to onOpenPermissions
        DomainError.NotConfigured, is DomainError.Validation -> stringResource(R.string.tracker_open_settings) to onOpenSettings
        else -> null to {}
    }
    Notice(text = error.message(), tone = StatusTone.NEGATIVE, actionLabel = label, onAction = action)
}

@Composable
private fun LastLocationCard(status: TrackingStatus, now: Instant) {
    val fix = status.activity.lastFix
    SectionCard(title = stringResource(R.string.tracker_last_location)) {
        if (fix == null) {
            Text(stringResource(R.string.tracker_no_fix_yet), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        PrivateTrackerMap(
            markers = listOf(MapMarker("self", fix.latitude, fix.longitude, MarkerStyle.SELF)),
            camera = MapCamera.Centered(fix.latitude, fix.longitude, zoom = 16.0),
            interactive = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        InfoRow(stringResource(R.string.tracker_coordinates), coordinates(fix.latitude, fix.longitude))
        InfoRow(stringResource(R.string.tracker_accuracy), accuracy(fix.accuracyM))
        InfoRow(stringResource(R.string.tracker_recorded), relativeTime(fix.recordedAt, now))
        fix.provider?.let { InfoRow(stringResource(R.string.tracker_provider), it) }
        if (fix.isMock) Text(stringResource(R.string.tracker_mock), color = MaterialTheme.colorScheme.error)
        (status.activity.lastRecord as? RecordResult.Skipped)?.let { skipped ->
            Text(
                text = when (skipped.reason) {
                    SkipReason.LOW_ACCURACY -> stringResource(R.string.tracker_skipped_low_accuracy)
                    SkipReason.TOO_CLOSE -> stringResource(R.string.tracker_skipped_too_close)
                    SkipReason.INVALID -> stringResource(R.string.tracker_skipped_invalid)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueCard(status: TrackingStatus, now: Instant) {
    SectionCard(title = stringResource(R.string.tracker_queue)) {
        InfoRow(
            stringResource(R.string.tracker_queue_size),
            stringResource(R.string.tracker_queue_value, status.queueSize, status.config.maxQueueSize),
        )
        if (status.queueNearlyFull) Notice(stringResource(R.string.tracker_queue_nearly_full))
        val upload = status.activity.lastUpload
        InfoRow(
            stringResource(R.string.tracker_last_upload),
            when (upload) {
                null -> stringResource(R.string.tracker_upload_never)
                is UploadResult.Completed -> {
                    val sent = pluralStringResource(R.plurals.tracker_upload_sent, upload.sent, upload.sent)
                    val time = relativeTime(status.activity.lastUploadAt, now)
                    if (upload.rejected == 0) {
                        stringResource(R.string.tracker_upload_completed, sent, time)
                    } else {
                        val rejected = pluralStringResource(R.plurals.tracker_upload_rejected, upload.rejected, upload.rejected)
                        stringResource(R.string.tracker_upload_completed_rejected, sent, rejected, time)
                    }
                }
                is UploadResult.RetryLater -> stringResource(R.string.tracker_upload_retry, upload.error.message())
                is UploadResult.Blocked -> stringResource(R.string.tracker_upload_blocked, upload.error.message())
            },
        )
    }
}
