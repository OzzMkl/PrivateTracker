package org.privatetracker.feature.devices.history

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.export.HistoryFormat
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.HistoryPeriod
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.Track
import org.privatetracker.core.domain.model.dayRange
import org.privatetracker.core.domain.usecase.server.ExportDeviceHistory
import org.privatetracker.core.domain.usecase.server.GetDeviceTrack
import org.privatetracker.core.domain.usecase.server.ObserveDeviceDetail
import org.privatetracker.core.domain.usecase.server.historyFileName
import org.privatetracker.core.map.MapPoint
import org.privatetracker.core.map.MapTrack
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the history shows: a quick range, or whole days picked in the calendar. */
sealed interface HistorySelection {
    data class Quick(val period: HistoryPeriod) : HistorySelection
    data class Days(val first: LocalDate, val last: LocalDate) : HistorySelection

    fun range(clock: Clock, zone: ZoneId): TimeRange = when (this) {
        is Quick -> period.range(clock.now(), zone)
        is Days -> dayRange(first, last, zone)
    }

    /** `TODAY` or `2026-10-01/2026-10-03`, to survive the process dying while "Save as" is open. */
    fun encode(): String = when (this) {
        is Quick -> period.name
        is Days -> "$first/$last"
    }

    companion object {
        fun decode(raw: String?): HistorySelection? {
            if (raw == null) return null
            HistoryPeriod.entries.firstOrNull { it.name == raw }?.let { return Quick(it) }
            val days = raw.split("/").mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            return if (days.size == 2) Days(days[0], days[1]) else null
        }
    }
}

sealed interface HistoryMessage {
    data class Exported(val count: Int) : HistoryMessage
    data object ExportFailed : HistoryMessage
}

data class DeviceHistoryUiState(
    val deviceName: String? = null,
    val selection: HistorySelection = HistorySelection.Quick(HistoryPeriod.TODAY),
    /** Null while the range loads. */
    val track: Track? = null,
    /** The same route with fewer points, as the map draws it. */
    val drawn: List<MapTrack> = emptyList(),
    val message: HistoryMessage? = null,
)

@HiltViewModel(assistedFactory = DeviceHistoryViewModel.Factory::class)
class DeviceHistoryViewModel @AssistedInject constructor(
    @Assisted rawDeviceId: String,
    @ApplicationContext private val context: Context,
    private val saved: SavedStateHandle,
    observeDetail: ObserveDeviceDetail,
    private val getTrack: GetDeviceTrack,
    private val exportHistory: ExportDeviceHistory,
    private val clock: Clock,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceId: String): DeviceHistoryViewModel
    }

    private val deviceId = DeviceId.parse(rawDeviceId)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val selection = MutableStateFlow(
        HistorySelection.decode(saved[SELECTION_KEY]) ?: HistorySelection.Quick(HistoryPeriod.TODAY),
    )

    /** Bumped on every tap, so tapping "Today" again reloads up to now. */
    private val requests = MutableStateFlow(0)
    private val loaded = MutableStateFlow<Loaded?>(null)

    /**
     * The range "Save as" was opened for: switching ranges meanwhile does not change what gets saved,
     * and the process dying while the picker is open does not lose it.
     */
    private var exportRange: TimeRange?
        get() {
            val from = saved.get<Long>(EXPORT_FROM_KEY) ?: return null
            val to = saved.get<Long>(EXPORT_TO_KEY) ?: return null
            return TimeRange(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
        }
        set(range) {
            saved[EXPORT_FROM_KEY] = range?.from?.toEpochMilli()
            saved[EXPORT_TO_KEY] = range?.to?.toEpochMilli()
        }
    private val message = MutableStateFlow<HistoryMessage?>(null)
    private val deviceName = deviceId?.let { id -> observeDetail(id).map { it?.overview?.device?.name } } ?: flowOf(null)

    /** A range loaded for [selection], and when its loading started, to time it until the map has drawn it. */
    private class Loaded(
        val request: Int,
        val selection: HistorySelection,
        val range: TimeRange,
        val track: Track,
        val drawn: List<MapTrack>,
        val started: TimeMark,
        val dataMillis: Long,
    )

    val state: StateFlow<DeviceHistoryUiState> =
        combine(deviceName, selection, requests, loaded, message) { name, selected, request, current, shown ->
            val ready = current?.takeIf { it.request == request }
            DeviceHistoryUiState(name, selected, ready?.track, ready?.drawn.orEmpty(), shown)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceHistoryUiState())

    init {
        val id = deviceId
        if (id != null) {
            viewModelScope.launch {
                requests.collectLatest { request ->
                    val selected = selection.value
                    val started = TimeSource.Monotonic.markNow()
                    val range = selected.range(clock, zone)
                    // Building and simplifying tens of thousands of points is CPU work, off the main thread.
                    val track = withContext(Dispatchers.Default) {
                        // Distance is worked out here, not on the main thread when the summary first shows it.
                        (getTrack(id, range) as? Outcome.Success)?.value?.also { it.distanceM }
                    } ?: Track(emptyList())
                    val drawn = withContext(Dispatchers.Default) { track.toMapTracks() }
                    loaded.value = Loaded(request, selected, range, track, drawn, started, started.elapsedNow().inWholeMilliseconds)
                }
            }
        }
    }

    fun onSelect(selected: HistorySelection) {
        selection.value = selected
        saved[SELECTION_KEY] = selected.encode()
        requests.value++
    }

    /** The map has drawn the current route: how long that took is the 0.6 exit criterion. */
    fun onTracksDrawn() {
        val current = loaded.value ?: return
        Log.i(
            TAG,
            "Historial: ${current.track.pointCount} posiciones (${current.drawn.sumOf { t -> t.segments.sumOf { it.size } }} dibujadas), " +
                "datos ${current.dataMillis} ms, en pantalla ${current.started.elapsedNow().inWholeMilliseconds} ms",
        )
    }

    /** What to suggest in "Save as", for the range on screen, which is also the one that gets saved. */
    fun fileName(format: HistoryFormat): String? {
        val current = loaded.value?.takeIf { it.request == requests.value } ?: return null
        exportRange = current.range
        return historyFileName(state.value.deviceName.orEmpty(), current.range, format, zone)
    }

    /** Writes the range that was on screen when "Save as" opened to the document the owner picked. */
    fun onExport(uri: Uri, format: HistoryFormat) {
        val id = deviceId
        val range = exportRange
        exportRange = null
        if (id == null || range == null) {
            // The picker already made the file; it stays empty, so at least say so.
            message.value = HistoryMessage.ExportFailed
            return
        }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        (exportHistory(id, range, format, writer) as? Outcome.Success)?.value
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Export failed", e)
                    null
                } catch (e: SecurityException) {
                    Log.w(TAG, "Export refused", e)
                    null
                }
            }
            message.value = count?.let(HistoryMessage::Exported) ?: HistoryMessage.ExportFailed
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    private fun Track.toMapTracks(): List<MapTrack> {
        if (isEmpty) return emptyList()
        val simplified = simplified(DRAW_TOLERANCE_M)
        return listOf(MapTrack("route", simplified.segments.map { segment -> segment.map { MapPoint(it.latitude, it.longitude) } }))
    }

    private companion object {
        const val TAG = "DeviceHistory"
        const val SELECTION_KEY = "selection"
        const val EXPORT_FROM_KEY = "exportFrom"
        const val EXPORT_TO_KEY = "exportTo"

        /** About what a street-level map shows apart; collapses a still phone's thousands of fixes into a few. */
        const val DRAW_TOLERANCE_M = 10.0
    }
}
