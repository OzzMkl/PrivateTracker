package org.privatetracker.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.LoadingBox
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.StatusBadge
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.permissionLabel
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.PermissionReport
import org.privatetracker.core.domain.model.PermissionStatus
import org.privatetracker.core.domain.model.Requirement
import org.privatetracker.core.location.AndroidPermissionChecker

/**
 * The permissions [mode] needs, each with why and a way to grant it. [onDone] carries the button
 * label, since onboarding finishes here while settings only goes back.
 */
@Composable
fun PermissionsRoute(
    mode: AppMode,
    doneLabel: String,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(mode) }

    val context = LocalContext.current
    // Names of permissions already asked for: asking again after a denial shows no dialog, so settings open instead.
    var asked by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh(mode)
    }

    PermissionsScreen(
        mode = mode,
        report = report,
        asked = asked,
        doneLabel = doneLabel,
        onGrant = { permission ->
            val runtime = AndroidPermissionChecker.runtimePermissions(permission)
            when {
                permission == AppPermission.BATTERY_OPTIMIZATION_EXEMPTION ->
                    context.openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                permission.name in asked || runtime.isEmpty() -> context.openAppSettings()
                else -> {
                    asked = asked + permission.name
                    launcher.launch(runtime.toTypedArray())
                }
            }
        },
        onDone = onDone,
        onBack = onBack,
    )
}

@Composable
fun PermissionsScreen(
    mode: AppMode,
    report: PermissionReport?,
    asked: Set<String>,
    doneLabel: String,
    onGrant: (AppPermission) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
) {
    ScreenScaffold(title = stringResource(R.string.permissions_title), onBack = onBack) { padding ->
        if (report == null) {
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
            Text(stringResource(R.string.permissions_intro), style = MaterialTheme.typography.bodyMedium)
            val preciseGranted = report.items.none { it.permission == AppPermission.PRECISE_LOCATION && !it.granted }
            report.items.forEach { item ->
                PermissionCard(
                    item = item,
                    mode = mode,
                    canAsk = item.permission != AppPermission.BACKGROUND_LOCATION || preciseGranted,
                    askedBefore = item.permission.name in asked,
                    onGrant = { onGrant(item.permission) },
                )
            }
            if (!report.allRequiredGranted) Notice(stringResource(R.string.permissions_missing_required))
            Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text(doneLabel) }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionStatus, mode: AppMode, canAsk: Boolean, askedBefore: Boolean, onGrant: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(permissionLabel(item.permission.name), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when {
                    item.granted -> StatusBadge(stringResource(R.string.permissions_granted), StatusTone.POSITIVE)
                    item.requirement == Requirement.REQUIRED -> StatusBadge(stringResource(R.string.permissions_required), StatusTone.NEGATIVE)
                    else -> StatusBadge(stringResource(R.string.permissions_recommended), StatusTone.WARNING)
                }
            }
            Text(
                reason(item.permission, mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!item.granted) {
                if (canAsk) {
                    OutlinedButton(onClick = onGrant, modifier = Modifier.align(Alignment.End)) {
                        val opensSettings = askedBefore || item.permission == AppPermission.BATTERY_OPTIMIZATION_EXEMPTION
                        Text(stringResource(if (opensSettings) R.string.permissions_open_settings else R.string.permissions_grant))
                    }
                } else {
                    Text(stringResource(R.string.permissions_needs_precise_first), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun reason(permission: AppPermission, mode: AppMode): String = stringResource(
    when (permission) {
        AppPermission.PRECISE_LOCATION -> R.string.why_precise_location
        AppPermission.BACKGROUND_LOCATION -> R.string.why_background_location
        AppPermission.NOTIFICATIONS -> if (mode.tracks) R.string.why_notifications_tracker else R.string.why_notifications_server
        AppPermission.LOCAL_NETWORK -> if (mode.serves) R.string.why_local_network_server else R.string.why_local_network_tracker
        AppPermission.BATTERY_OPTIMIZATION_EXEMPTION -> R.string.why_battery
    },
)

private fun Context.openAppSettings() =
    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some vendor builds lack a settings screen; the user can still grant it by hand.
    }
}
