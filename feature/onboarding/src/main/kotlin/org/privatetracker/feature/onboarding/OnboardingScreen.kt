package org.privatetracker.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.privatetracker.core.domain.model.AppMode

/** First run: choose a mode, then grant its permissions. Saving the mode at the end leaves onboarding. */
@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel()) {
    var chosen by rememberSaveable { mutableStateOf<AppMode?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }

    val mode = chosen
    if (confirmed && mode != null) {
        BackHandler { confirmed = false }
        PermissionsRoute(
            mode = mode,
            doneLabel = stringResource(R.string.onboarding_finish),
            onDone = { viewModel.onFinish(mode) },
            onBack = { confirmed = false },
        )
    } else {
        ModeSelectionScreen(selected = chosen, onSelect = { chosen = it }, onContinue = { confirmed = true })
    }
}

@Composable
fun ModeSelectionScreen(selected: AppMode?, onSelect: (AppMode) -> Unit, onContinue: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.onboarding_tagline), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.onboarding_question), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeOption(AppMode.TRACKER, R.string.mode_tracker, R.string.mode_tracker_body, selected, onSelect)
                ModeOption(AppMode.SERVER, R.string.mode_server, R.string.mode_server_body, selected, onSelect)
                ModeOption(AppMode.TRACKER_AND_SERVER, R.string.mode_both, R.string.mode_both_body, selected, onSelect)
            }
            Button(onClick = onContinue, enabled = selected != null, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

/** One of the three modes as a card with a radio button. */
@Composable
private fun ModeOption(mode: AppMode, title: Int, body: Int, selected: AppMode?, onSelect: (AppMode) -> Unit) {
    val isSelected = mode == selected
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) }),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

