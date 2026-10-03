package org.privatetracker.feature.tracker.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.usecase.tracker.CurrentServer
import org.privatetracker.core.qr.QrScanner
import org.privatetracker.feature.tracker.R

@Composable
fun TrackerPairingRoute(link: String?, onDone: () -> Unit, onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val viewModel = hiltViewModel<TrackerPairingViewModel, TrackerPairingViewModel.Factory>(
        key = "pairing-$link",
        creationCallback = { factory -> factory.create(link) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrackerPairingScreen(
        state = state,
        onScanned = viewModel::onScanned,
        onConfirm = viewModel::onConfirm,
        onScanAgain = viewModel::onScanAgain,
        onDone = onDone,
        onBack = onBack,
        onOpenPermissions = onOpenPermissions,
    )
}

@Composable
fun TrackerPairingScreen(
    state: TrackerPairingUiState,
    onScanned: (String) -> Boolean,
    onConfirm: () -> Unit,
    onScanAgain: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.tracker_pairing_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = state.step) {
                PairingStep.Scanning -> Scanner(state.foreignCode, onScanned)
                is PairingStep.Confirm -> InviteCard(step.invite, state.current) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onScanAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tracker_pairing_cancel)) }
                        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tracker_pairing_confirm)) }
                    }
                }
                is PairingStep.Pairing -> InviteCard(step.invite, state.current) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(stringResource(R.string.tracker_pairing_working), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is PairingStep.Paired -> {
                    Notice(
                        stringResource(R.string.tracker_pairing_done, step.result.serverName, step.result.serverUrl),
                        tone = StatusTone.POSITIVE,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tracker_pairing_finish)) }
                }
                is PairingStep.Failed -> {
                    Notice(step.error.message(), tone = StatusTone.NEGATIVE)
                    // On Android 17 a phone without local network access sees every LAN address time out.
                    if (step.error is DomainError.Network) {
                        Notice(
                            stringResource(R.string.tracker_pairing_network_hint),
                            actionLabel = stringResource(R.string.tracker_open_permissions),
                            onAction = onOpenPermissions,
                        )
                    }
                    Button(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tracker_pairing_scan_again)) }
                }
            }
        }
    }
}

@Composable
private fun Scanner(foreignCode: Boolean, onScanned: (String) -> Boolean) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }
    SectionCard(title = stringResource(R.string.tracker_pairing_scan_title)) {
        Text(stringResource(R.string.tracker_pairing_scan_body), style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            QrScanner(
                onScanned = onScanned,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            if (foreignCode) Notice(stringResource(R.string.tracker_pairing_foreign_code), tone = StatusTone.WARNING)
        } else if (!asked) {
            Button(onClick = { request.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tracker_pairing_allow_camera))
            }
        } else {
            // Denied: Android will not ask again, only the system settings can grant it now.
            Notice(
                stringResource(R.string.tracker_pairing_camera_denied),
                actionLabel = stringResource(R.string.tracker_pairing_open_app_settings),
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        }
    }
}

@Composable
private fun InviteCard(invite: PairingInvite, current: CurrentServer?, actions: @Composable () -> Unit) {
    val fingerprint = keyFingerprint(invite.serverKey) ?: "—"
    SectionCard(title = stringResource(R.string.tracker_pairing_confirm_title, invite.serverName)) {
        Text(stringResource(R.string.tracker_pairing_confirm_body), style = MaterialTheme.typography.bodyMedium)
        InfoRow(stringResource(R.string.tracker_pairing_server_fingerprint), fingerprint)
        InfoRow(
            stringResource(R.string.tracker_pairing_addresses),
            pluralStringResource(R.plurals.tracker_pairing_address_count, invite.serverUrls.size, invite.serverUrls.size),
        )
        invite.serverUrls.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Anyone can hand this phone a pairing link: replacing a working pairing must not go unnoticed.
        if (current != null && current.keyFingerprint != fingerprint) {
            Notice(
                stringResource(R.string.tracker_pairing_replaces, current.url, current.keyFingerprint ?: "—", invite.serverName),
                tone = StatusTone.WARNING,
            )
        }
        actions()
    }
}
