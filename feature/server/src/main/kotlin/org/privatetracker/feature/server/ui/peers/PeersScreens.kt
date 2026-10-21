package org.privatetracker.feature.server.ui.peers

import android.Manifest
import android.content.ClipData
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.dateTime
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.designsystem.text.relativeTime
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.qr.QrCode
import org.privatetracker.core.qr.QrScanner
import org.privatetracker.feature.server.R
import java.time.Duration

// Paired servers, from 1.0: the list, an invite for another server, and joining another's.

@Composable
fun PeersRoute(onInvite: () -> Unit, onJoin: () -> Unit, onBack: () -> Unit, viewModel: PeersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PeersScreen(state, onInvite = onInvite, onJoin = onJoin, onRemove = viewModel::onRemove, onBack = onBack)
}

@Composable
fun PeersScreen(state: PeersUiState, onInvite: () -> Unit, onJoin: () -> Unit, onRemove: (Peer) -> Unit, onBack: () -> Unit) {
    var removing by remember { mutableStateOf<Peer?>(null) }
    ScreenScaffold(title = stringResource(R.string.server_peers_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.server_peers_body), style = MaterialTheme.typography.bodyMedium)
            if (state.loading) {
                LoadingBox(Modifier.fillMaxWidth())
            } else if (state.peers.isEmpty()) {
                Notice(stringResource(R.string.server_peers_none))
            }
            state.peers.forEach { peer -> PeerCard(peer, onRemove = { removing = peer }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onJoin, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_peers_join)) }
                Button(onClick = onInvite, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_peers_invite)) }
            }
        }
    }
    removing?.let { peer ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.server_peers_remove_title, peer.name)) },
            text = { Text(stringResource(R.string.server_peers_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(peer)
                }) { Text(stringResource(R.string.server_peers_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.server_cancel)) } },
        )
    }
}

@Composable
private fun PeerCard(peer: Peer, onRemove: () -> Unit) {
    val now = rememberNow()
    SectionCard(title = peer.name) {
        val error = peer.sync.lastError
        val last = peer.sync.lastExchangeAt
        when {
            error == "PEER_UNKNOWN" -> StatusBadge(stringResource(R.string.server_peers_state_unknown), StatusTone.NEGATIVE)
            error == "SERVER_IDENTITY_MISMATCH" -> StatusBadge(stringResource(R.string.server_peers_state_other), StatusTone.NEGATIVE)
            error != null -> StatusBadge(stringResource(R.string.server_peers_state_unreachable), StatusTone.WARNING)
            last == null -> StatusBadge(stringResource(R.string.server_peers_state_new), StatusTone.NEUTRAL)
            else -> StatusBadge(stringResource(R.string.server_peers_state_ok), StatusTone.POSITIVE)
        }
        InfoRow(stringResource(R.string.server_peers_fingerprint), peer.fingerprint)
        InfoRow(stringResource(R.string.server_peers_last_exchange), last?.let { relativeTime(it, now) } ?: stringResource(R.string.server_peers_never))
        InfoRow(stringResource(R.string.server_peers_paired_at), dateTime(peer.pairedAt))
        peer.addresses.firstOrNull()?.let { InfoRow(stringResource(R.string.server_peers_address), it) }
        OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_peers_remove)) }
    }
}

@Composable
fun PeerInviteRoute(onBack: () -> Unit, viewModel: PeerInviteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PeerInviteScreen(state, onRenew = viewModel::onRenew, onBack = onBack)
}

@Composable
fun PeerInviteScreen(state: PeerInviteUiState, onRenew: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.server_peers_invite_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val joined = state.joined
            when {
                state.error != null -> Notice(state.error.message(), tone = StatusTone.NEGATIVE, actionLabel = stringResource(R.string.server_pairing_retry), onAction = onRenew)
                joined != null -> {
                    Notice(stringResource(R.string.server_peers_invite_done, joined.name, joined.fingerprint), tone = StatusTone.POSITIVE)
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_pairing_close)) }
                }
                state.link == null || state.invite == null -> LoadingBox(Modifier.fillMaxWidth())
                else -> InviteCard(state.invite, state.link, onRenew)
            }
            state.serverKeyFingerprint?.let { InfoRow(stringResource(R.string.server_key_fingerprint), it) }
        }
    }
}

@Composable
private fun InviteCard(invite: PairingInvite, link: String, onRenew: () -> Unit) {
    val now = rememberNow(periodMillis = 1_000)
    val left = Duration.between(now, invite.expiresAt)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.server_peers_invite_scan_title)) {
        Text(stringResource(R.string.server_peers_invite_body), style = MaterialTheme.typography.bodyMedium)
        if (left.isNegative || left.isZero) {
            Notice(stringResource(R.string.server_pairing_expired), tone = StatusTone.WARNING, actionLabel = stringResource(R.string.server_pairing_new_code), onAction = onRenew)
            return@SectionCard
        }
        QrCode(
            content = link,
            contentDescription = stringResource(R.string.server_peers_invite_qr_description),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            // Not toSecondsPart(): Android has it only from API 31.
            stringResource(R.string.server_pairing_expires_in, left.toMinutes(), left.seconds % 60),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.server_peers_invite_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(link, link))) } },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.server_copy)) }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, null))
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.server_peers_share)) }
        }
    }
}

@Composable
fun PeerJoinRoute(link: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<PeerJoinViewModel, PeerJoinViewModel.Factory>(
        key = "peer-join-$link",
        creationCallback = { factory -> factory.create(link) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    PeerJoinScreen(state, onRead = viewModel::onRead, onConfirm = viewModel::onConfirm, onReadAgain = viewModel::onReadAgain, onDone = onDone, onBack = onBack)
}

@Composable
fun PeerJoinScreen(
    state: PeerJoinUiState,
    onRead: (String) -> Boolean,
    onConfirm: () -> Unit,
    onReadAgain: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.server_peers_join_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = state.step) {
                JoinStep.Reading -> Reader(state.foreignCode, onRead)
                is JoinStep.Confirm -> JoinCard(step.invite, state.serverRunning) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReadAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_cancel)) }
                        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_peers_join_confirm)) }
                    }
                }
                is JoinStep.Joining -> JoinCard(step.invite, state.serverRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(stringResource(R.string.server_peers_join_working), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is JoinStep.Joined -> {
                    Notice(stringResource(R.string.server_peers_join_done, step.peer.name), tone = StatusTone.POSITIVE)
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_pairing_close)) }
                }
                is JoinStep.Failed -> {
                    Notice(step.error.message(), tone = StatusTone.NEGATIVE)
                    Button(onClick = onReadAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_peers_join_again)) }
                }
            }
        }
    }
}

/** The camera, or the link pasted: a Linux server's invite usually arrives as text. */
@Composable
private fun Reader(foreignCode: Boolean, onRead: (String) -> Boolean) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }
    var pasted by rememberSaveable { mutableStateOf("") }
    SectionCard(title = stringResource(R.string.server_peers_join_scan_title)) {
        Text(stringResource(R.string.server_peers_join_scan_body), style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            QrScanner(
                onScanned = onRead,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else if (!asked) {
            OutlinedButton(onClick = { request.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.server_peers_allow_camera))
            }
        } else {
            // Denied: Android will not ask again, only the system settings can grant it now.
            Notice(
                stringResource(R.string.server_peers_camera_denied),
                actionLabel = stringResource(R.string.server_peers_open_app_settings),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                },
            )
        }
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text(stringResource(R.string.server_peers_join_paste)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onRead(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.server_peers_join_continue))
        }
        if (foreignCode) Notice(stringResource(R.string.server_peers_join_foreign), tone = StatusTone.WARNING)
    }
}

@Composable
private fun JoinCard(invite: PairingInvite, serverRunning: Boolean, actions: @Composable () -> Unit) {
    SectionCard(title = stringResource(R.string.server_peers_join_confirm_title, invite.serverName)) {
        Text(stringResource(R.string.server_peers_join_confirm_body), style = MaterialTheme.typography.bodyMedium)
        if (!serverRunning) Notice(stringResource(R.string.server_peers_join_server_stopped), tone = StatusTone.WARNING)
        InfoRow(stringResource(R.string.server_peers_fingerprint), keyFingerprint(invite.serverKey) ?: "—")
        invite.serverUrls.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
