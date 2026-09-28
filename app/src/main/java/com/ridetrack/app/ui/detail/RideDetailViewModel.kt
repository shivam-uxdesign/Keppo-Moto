package com.ridetrack.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.RideTrack
import com.ridetrack.app.ui.common.routePoints
import com.ridetrack.app.ui.components.ChartSeries
import com.ridetrack.app.ui.components.GeoPoint
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chart-ready data derived once from the persisted track. */
class TrackData(val track: RideTrack) {
    val samples: List<TelemetrySample> = track.samples
    val route: List<GeoPoint> = samples.routePoints()
    val speed = series { s -> s.speedMps?.let { it * 3.6 } }
    val lean = series(symmetric = true) { it.leanDeg }
    val gForce = series(floorZero = true) { it.combinedG }
    val elevation = series { it.altitudeM }
    /** Number of route points up to and including each sample (for the travelled-route highlight). */
    val routeCountAt: IntArray = IntArray(samples.size).also { out ->
        var n = 0
        samples.forEachIndexed { i, s ->
            if (s.latitude != null && s.longitude != null) n++
            out[i] = n
        }
    }
    /** Where the scrubber starts: the moment of top speed, the most interesting point. */
    val initialIndex: Int = samples.indices.maxByOrNull { samples[it].speedMps ?: -1.0 } ?: 0

    private fun series(symmetric: Boolean = false, floorZero: Boolean = false, f: (TelemetrySample) -> Double?) =
        ChartSeries(FloatArray(samples.size) { i -> f(samples[i])?.toFloat() ?: Float.NaN }, symmetric, floorZero)
}

enum class ChartKind(val label: String) { SPEED("Speed"), LEAN("Lean"), G("G"), ELEVATION("Elevation") }

data class DetailUiState(
    val loading: Boolean = true,
    val ride: Ride? = null,
    val bikeName: String? = null,
    val data: TrackData? = null,
)

class RideDetailViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val data = MutableStateFlow<TrackData?>(null)
    private val _scrub = MutableStateFlow<Float?>(null)
    val scrub: StateFlow<Float?> = _scrub.asStateFlow()

    val state: StateFlow<DetailUiState> = combine(c.rides.observeRide(rideId), c.bikes.observeBikes(), data) { ride, bikes, d ->
        DetailUiState(
            loading = d == null,
            ride = ride,
            bikeName = bikes.firstOrNull { it.id == ride?.bikeId }?.displayName,
            data = d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            val d = withContext(Dispatchers.Default) { TrackData(c.rides.track(rideId)) }
            data.value = d
            if (_scrub.value == null && d.samples.size >= 2) {
                _scrub.value = d.initialIndex.toFloat() / (d.samples.size - 1)
            }
        }
    }

    fun scrubTo(fraction: Float) {
        _scrub.value = fraction.coerceIn(0f, 1f)
    }

    fun scrubToTime(timeMillis: Long) {
        val samples = data.value?.samples ?: return
        if (samples.size < 2) return
        val start = samples.first().timeMillis
        val end = samples.last().timeMillis
        if (end <= start) return
        // Charts are indexed by sample; find the sample index for this time.
        val idx = samples.indexOfFirst { it.timeMillis >= timeMillis }.let { if (it < 0) samples.size - 1 else it }
        _scrub.value = idx.toFloat() / (samples.size - 1)
    }

    private val _chart = MutableStateFlow(ChartKind.SPEED)
    val chart: StateFlow<ChartKind> = _chart.asStateFlow()
    fun selectChart(kind: ChartKind) {
        _chart.value = kind
    }

    fun clearScrub() {
        _scrub.value = null
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { c.rides.rename(rideId, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            c.rides.delete(rideId)
            c.moments.deleteFilesForRide(rideId)
            onDeleted()
        }
    }
}
