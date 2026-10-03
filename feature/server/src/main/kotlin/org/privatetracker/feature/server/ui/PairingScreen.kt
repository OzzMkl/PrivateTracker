package org.privatetracker.feature.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.InfoRow
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.component.rememberNow
import org.privatetracker.core.designsystem.text.message
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.qr.QrCode
import org.privatetracker.feature.server.R
import java.time.Duration

@Composable
fun PairingRoute(onBack: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PairingScreen(state, onRenew = viewModel::onRenew, onBack = onBack)
}

@Composable
fun PairingScreen(state: PairingUiState, onRenew: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.server_pairing_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val paired = state.paired
            when {
                state.error != null -> Notice(state.error.message(), tone = StatusTone.NEGATIVE, actionLabel = stringResource(R.string.server_pairing_retry), onAction = onRenew)
                paired != null -> {
                    Notice(
                        stringResource(R.string.server_pairing_done, paired.name, paired.publicKey?.let(::keyFingerprint) ?: "—"),
                        tone = StatusTone.POSITIVE,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onRenew, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_pairing_another)) }
                        Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_pairing_close)) }
                    }
                }
                state.link == null || state.invite == null -> LoadingBox(Modifier.fillMaxWidth())
                else -> InviteCard(state, onRenew)
            }
            state.serverKeyFingerprint?.let { InfoRow(stringResource(R.string.server_key_fingerprint), it) }
        }
    }
}

@Composable
private fun InviteCard(state: PairingUiState, onRenew: () -> Unit) {
    val invite = checkNotNull(state.invite)
    val now = rememberNow(periodMillis = 1_000)
    val left = Duration.between(now, invite.expiresAt)
    SectionCard(title = stringResource(R.string.server_pairing_scan_title)) {
        Text(stringResource(R.string.server_pairing_scan_body), style = MaterialTheme.typography.bodyMedium)
        if (left.isNegative || left.isZero) {
            Notice(stringResource(R.string.server_pairing_expired), tone = StatusTone.WARNING, actionLabel = stringResource(R.string.server_pairing_new_code), onAction = onRenew)
            return@SectionCard
        }
        QrCode(
            content = checkNotNull(state.link),
            contentDescription = stringResource(R.string.server_pairing_qr_description),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            stringResource(R.string.server_pairing_expires_in, left.toMinutes(), left.toSecondsPart()),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
