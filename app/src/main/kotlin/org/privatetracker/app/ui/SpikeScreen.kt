package org.privatetracker.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.app.server.ServerState
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.UploadResult

// SPIKE UI: one screen to exercise server, tracker and permissions. Real screens come with milestone 5.

@Composable
fun SpikeRoute(viewModel: SpikeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SpikeScreen(
        state = state,
        onStartServer = viewModel::startServer,
        onStopServer = viewModel::stopServer,
        onTestConnection = viewModel::testConnection,
        onStartTracking = viewModel::startTracking,
        onStopTracking = viewModel::stopTracking,
    )
}

@Composable
fun SpikeScreen(
    state: SpikeUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onTestConnection: (String) -> Unit,
    onStartTracking: (String, String) -> Unit,
    onStopTracking: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PrivateTracker · spike 0.1", style = MaterialTheme.typography.titleLarge)
            PermissionsCard()
            ServerCard(state, onStartServer, onStopServer)
            TrackerCard(state, onTestConnection, onStartTracking, onStopTracking)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    // Android 11+: "all the time" must be asked separately, after foreground location.
    val requestBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Section("Permisos") {
        refresh.let { _ ->
            val missing = requiredPermissions().filterNot(context::granted)
            requiredPermissions().forEach { permission ->
                Text((if (context.granted(permission)) "✓ " else "✗ ") + permission.substringAfterLast('.'))
            }
            val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            Text((if (context.granted(background)) "✓ " else "✗ ") + "ACCESS_BACKGROUND_LOCATION")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { request.launch(missing.toTypedArray()) }, enabled = missing.isNotEmpty()) {
                    Text("Conceder permisos")
                }
                OutlinedButton(
                    onClick = { requestBackground.launch(background) },
                    enabled = context.granted(Manifest.permission.ACCESS_FINE_LOCATION) && !context.granted(background),
                ) { Text("Todo el tiempo") }
            }
        }
    }
}

@Composable
private fun ServerCard(state: SpikeUiState, onStart: () -> Unit, onStop: () -> Unit) {
    Section("Servidor") {
        Text(
            when (val server = state.server) {
                ServerState.Stopped -> "Detenido"
                ServerState.Starting -> "Iniciando…"
                is ServerState.Running -> "Activo en el puerto ${server.port}"
                is ServerState.Failed -> "Error: ${server.message}"
            },
        )
        state.serverUrls.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state.server !is ServerState.Running) { Text("Iniciar servidor") }
            OutlinedButton(onClick = onStop, enabled = state.server is ServerState.Running) { Text("Detener servidor") }
        }
        Text("Dispositivos (${state.devices.size})", fontWeight = FontWeight.SemiBold)
        state.devices.forEach { overview ->
            val location = overview.lastLocation
            Text(
                "${overview.device.name} · ${overview.status}" +
                    (location?.let { "\n%.5f, %.5f ±%.0f m · %s".format(it.latitude, it.longitude, it.accuracyM ?: 0f, it.recordedAt) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TrackerCard(
    state: SpikeUiState,
    onTestConnection: (String) -> Unit,
    onStart: (String, String) -> Unit,
    onStop: () -> Unit,
) {
    // Keyed on the stored values, so the fields pick up the real configuration once it loads.
    var serverUrl by rememberSaveable(state.trackerConfig.serverUrl) {
        mutableStateOf(state.trackerConfig.serverUrl.ifBlank { "http://127.0.0.1:8787" })
    }
    var interval by rememberSaveable(state.trackerConfig.intervalSeconds) { mutableStateOf(state.trackerConfig.intervalSeconds.toString()) }
    val tracker = state.tracker

    Section("Tracker") {
        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("URL del servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(interval, { interval = it }, label = { Text("Intervalo (s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onTestConnection(serverUrl) }) { Text("Probar conexión") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStart(serverUrl, interval) }, enabled = !tracker.running) { Text("Iniciar rastreo") }
            OutlinedButton(onClick = onStop, enabled = tracker.running) { Text("Detener") }
        }
        Text(if (tracker.running) "Rastreando" else "Detenido")
        tracker.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        tracker.lastFix?.let { Text("Último fix: %.5f, %.5f (%s)".format(it.latitude, it.longitude, it.provider)) }
        tracker.lastRecord?.let {
            Text(
                when (it) {
                    is RecordResult.Recorded -> "Guardado en cola"
                    is RecordResult.Skipped -> "Descartado: ${it.reason}"
                },
            )
        }
        Text("En cola: ${state.queueSize}")
        tracker.lastUpload?.let {
            Text(
                "Último envío: " + when (it) {
                    is UploadResult.Completed -> "${it.sent} enviadas, ${it.rejected} rechazadas"
                    is UploadResult.RetryLater -> "reintentar (${it.error.code})"
                    is UploadResult.Blocked -> "bloqueado (${it.error.code})"
                },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
