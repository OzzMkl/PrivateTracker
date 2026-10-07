package org.privatetracker.feature.devices.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.privatetracker.core.designsystem.component.Notice
import org.privatetracker.core.designsystem.component.ScreenScaffold
import org.privatetracker.core.designsystem.component.SectionCard
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.designsystem.text.dateTime
import org.privatetracker.core.domain.export.HistoryFormat
import org.privatetracker.core.domain.model.HistoryPeriod
import org.privatetracker.core.domain.model.Track
import org.privatetracker.core.map.MapMarker
import org.privatetracker.core.map.MarkerStyle
import org.privatetracker.core.map.PrivateTrackerMap
import org.privatetracker.feature.devices.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DeviceHistoryRoute(deviceId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DeviceHistoryViewModel, DeviceHistoryViewModel.Factory>(
        key = "history-$deviceId",
        creationCallback = { factory -> factory.create(deviceId) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // One launcher per format: "Save as" takes its file type when it is set up.
    val saveGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(HistoryFormat.GPX.mimeType)) { uri ->
        uri?.let { viewModel.onExport(it, HistoryFormat.GPX) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(HistoryFormat.CSV.mimeType)) { uri ->
        uri?.let { viewModel.onExport(it, HistoryFormat.CSV) }
    }
    DeviceHistoryScreen(
        state = state,
        onBack = onBack,
        onSelect = viewModel::onSelect,
        onTracksDrawn = viewModel::onTracksDrawn,
        onExport = { format ->
            val name = viewModel.fileName(format) ?: return@DeviceHistoryScreen
            if (format == HistoryFormat.GPX) saveGpx.launch(name) else saveCsv.launch(name)
        },
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun DeviceHistoryScreen(
    state: DeviceHistoryUiState,
    onBack: () -> Unit,
    onSelect: (HistorySelection) -> Unit,
    onTracksDrawn: () -> Unit,
    onExport: (HistoryFormat) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var pickingDays by rememberSaveable { mutableStateOf(false) }
    val exported = state.message?.let { message ->
        when (message) {
            is HistoryMessage.Exported -> pluralStringResource(R.plurals.device_history_exported, message.count, message.count)
            HistoryMessage.ExportFailed -> stringResource(R.string.device_history_export_failed)
        }
    }
    LaunchedEffect(exported) {
        if (exported != null) {
            snackbar.showSnackbar(exported)
            onMessageShown()
        }
    }

    ScreenScaffold(
        title = state.deviceName?.let { stringResource(R.string.device_history_title_named, it) } ?: stringResource(R.string.device_history_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = state.track?.isEmpty == false) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_history_export_gpx)) }, onClick = {
                        menuOpen = false
                        onExport(HistoryFormat.GPX)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_history_export_csv)) }, onClick = {
                        menuOpen = false
                        onExport(HistoryFormat.CSV)
                    })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RangeChips(state.selection, onSelect, onPickDays = { pickingDays = true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val track = state.track
                PrivateTrackerMap(
                    markers = track?.endpoints().orEmpty(),
                    tracks = state.drawn,
                    onTracksDrawn = onTracksDrawn,
                    modifier = Modifier.fillMaxSize(),
                )
                when {
                    track == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    track.isEmpty -> Notice(
                        stringResource(R.string.device_history_empty),
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                        tone = StatusTone.NEUTRAL,
                    )
                }
            }
            state.track?.takeIf { !it.isEmpty }?.let { Summary(it) }
        }
    }
    if (pickingDays) DaysDialog(onPick = { onSelect(it); pickingDays = false }, onDismiss = { pickingDays = false })
}

@Composable
private fun RangeChips(selection: HistorySelection, onSelect: (HistorySelection) -> Unit, onPickDays: () -> Unit) {
    val quick = listOf(
        HistoryPeriod.TODAY to R.string.device_history_today,
        HistoryPeriod.YESTERDAY to R.string.device_history_yesterday,
        HistoryPeriod.LAST_7_DAYS to R.string.device_history_7_days,
        HistoryPeriod.LAST_30_DAYS to R.string.device_history_30_days,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        quick.forEach { (period, label) ->
            FilterChip(
                selected = selection == HistorySelection.Quick(period),
                onClick = { onSelect(HistorySelection.Quick(period)) },
                label = { Text(stringResource(label)) },
            )
        }
        val days = selection as? HistorySelection.Days
        FilterChip(
            selected = days != null,
            onClick = onPickDays,
            label = { Text(days?.label() ?: stringResource(R.string.device_history_days)) },
            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }
}

@Composable
private fun Summary(track: Track) {
    SectionCard(title = stringResource(R.string.device_history_summary), modifier = Modifier.padding(16.dp)) {
        val distance = track.distanceM
        Text(
            pluralStringResource(R.plurals.device_history_positions, track.pointCount, track.pointCount) + " · " +
                if (distance >= 1_000) stringResource(R.string.device_history_km, distance / 1_000) else stringResource(R.string.device_history_m, distance),
            style = MaterialTheme.typography.bodyLarge,
        )
        val start = track.start
        val end = track.end
        if (start != null && end != null) {
            Text(
                stringResource(R.string.device_history_span, dateTime(start), dateTime(end)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Full screen, as Material lays out a range picker on phones: the docked dialog needs 360 dp and clips on narrower screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysDialog(onPick: (HistorySelection.Days) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    // The calendar grid needs 360 dp too; narrower screens start with the typed dates, which fit.
    val narrow = LocalConfiguration.current.screenWidthDp < CALENDAR_MIN_WIDTH_DP
    val picker = rememberDateRangePickerState(
        initialDisplayMode = if (narrow) DisplayMode.Input else DisplayMode.Picker,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcTimeMillis.toUtcDate().isAfter(today)
        },
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    val first = picker.selectedStartDateMillis
                    TextButton(
                        enabled = first != null,
                        onClick = {
                            val start = first?.toUtcDate() ?: return@TextButton
                            onPick(HistorySelection.Days(start, picker.selectedEndDateMillis?.toUtcDate() ?: start))
                        },
                    ) { Text(stringResource(R.string.device_history_show)) }
                }
                DateRangePicker(state = picker, modifier = Modifier.weight(1f))
            }
        }
    }
}

private const val CALENDAR_MIN_WIDTH_DP = 360

/** The picker hands out midnight UTC of the chosen day. */
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun HistorySelection.Days.label(): String =
    if (first == last) DayFormat.format(first) else "${DayFormat.format(first)} – ${DayFormat.format(last)}"

/** Where the route starts and where it ends, the end drawn on top. */
private fun Track.endpoints(): List<MapMarker> {
    val first = segments.firstOrNull()?.firstOrNull() ?: return emptyList()
    val last = segments.last().last()
    return listOf(
        MapMarker("start", first.latitude, first.longitude, MarkerStyle.ROUTE_START),
        MapMarker("end", last.latitude, last.longitude, MarkerStyle.ROUTE_END),
    )
}
