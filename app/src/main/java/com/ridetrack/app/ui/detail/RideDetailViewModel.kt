package com.ridetrack.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.RideTrack
import com.ridetrack.app.ui.common.indexAt
import com.ridetrack.app.ui.common.positionAt
import com.ridetrack.app.ui.common.routePoints
import com.ridetrack.app.ui.components.ChartWindow
import com.ridetrack.telemetry.math.Geo
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val startMillis: Long = samples.firstOrNull()?.timeMillis ?: 0L
    val durationMillis: Long = ((samples.lastOrNull()?.timeMillis ?: 0L) - startMillis).coerceAtLeast(0L)

    /** Where each ride-dynamics entry happened, as a sample index (null when not recorded). */
    val maxLeftIndex: Int? = samples.indices.filter { (samples[it].leanDeg ?: 0.0) < 0 }.minByOrNull { samples[it].leanDeg!! }
    val maxRightIndex: Int? = samples.indices.filter { (samples[it].leanDeg ?: 0.0) > 0 }.maxByOrNull { samples[it].leanDeg!! }
    val hardestBrakeIndex: Int? = samples.indices.filter { (samples[it].longitudinalG ?: 0.0) < 0 }.minByOrNull { samples[it].longitudinalG!! }
    val strongestAccelIndex: Int? = samples.indices.filter { (samples[it].longitudinalG ?: 0.0) > 0 }.maxByOrNull { samples[it].longitudinalG!! }

    fun fraction(index: Int): Float = if (samples.size < 2) 0f else index.toFloat() / (samples.size - 1)
    fun fractionAt(timeMillis: Long): Float = fraction(samples.indexAt(timeMillis).coerceAtLeast(0))
    fun index(fraction: Float): Int = (fraction * (samples.size - 1)).roundToInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0))

    /** Direction of travel around [index], from positions a few seconds either side (GPS heading is noisy when slow). */
    fun bearingAt(index: Int): Float? {
        val s = samples.getOrNull(index) ?: return null
        val a = samples.positionAt(s.timeMillis - BEARING_SPAN_MS) ?: return s.headingDeg?.toFloat()
        val b = samples.positionAt(s.timeMillis + BEARING_SPAN_MS) ?: return s.headingDeg?.toFloat()
        if (Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude) < 3.0) return s.headingDeg?.toFloat()
        return Geo.bearingDeg(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()
    }

    private fun series(symmetric: Boolean = false, floorZero: Boolean = false, f: (TelemetrySample) -> Double?) =
        ChartSeries(FloatArray(samples.size) { i -> f(samples[i])?.toFloat() ?: Float.NaN }, symmetric, floorZero)
}

private const val BEARING_SPAN_MS = 4_000L

enum class ChartKind(val label: String) { SPEED("Speed"), LEAN("Lean"), G("G"), ELEVATION("Elevation") }

data class DetailUiState(
    val loading: Boolean = true,
    val ride: Ride? = null,
    val bikeName: String? = null,
    val data: TrackData? = null,
)

data class Playback(val playing: Boolean = false, val speedIndex: Int = 1, val threeD: Boolean = false) {
    val speedLabel: String get() = "${PLAY_SPEEDS[speedIndex].toInt()}×"
}

/** Real-time multiples; 60× plays an hour's ride in a minute. */
val PLAY_SPEEDS = listOf(1.0, 4.0, 16.0, 60.0)
private const val FRAME_MILLIS = 33L
private const val JUMP_WINDOW_MS = 10 * 60_000L
private const val JUMP_MIN_SPAN_MS = 60_000L

class RideDetailViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val data = MutableStateFlow<TrackData?>(null)
    private val _scrub = MutableStateFlow<Float?>(null)
    val scrub: StateFlow<Float?> = _scrub.asStateFlow()
    private val _window = MutableStateFlow(ChartWindow.Full)
    val window: StateFlow<ChartWindow> = _window.asStateFlow()
    private val _playback = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = _playback.asStateFlow()
    private var playJob: Job? = null

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
        val f = fraction.coerceIn(0f, 1f)
        _scrub.value = f
        _window.value = _window.value.follow(f)
    }

    fun setWindow(w: ChartWindow) {
        _window.value = w
    }

    fun resetZoom() {
        _window.value = ChartWindow.Full
    }

    /** Smallest zoom window: about a minute of riding. */
    fun minSpan(): Float = data.value?.durationMillis?.takeIf { it > 0 }?.let { (JUMP_MIN_SPAN_MS.toFloat() / it).coerceAtMost(1f) } ?: 1f

    /**
     * Jumps to a moment of the ride (a dynamics entry, an event, a moment) and zooms the chart
     * to a few minutes around it so the spot is easy to read.
     */
    fun jumpToIndex(index: Int) {
        val d = data.value ?: return
        pause()
        val f = d.fraction(index)
        _scrub.value = f
        val around = d.durationMillis.takeIf { it > 0 }?.let { (JUMP_WINDOW_MS.toFloat() / it).coerceAtMost(1f) } ?: 1f
        _window.value = ChartWindow.around(f, minOf(around, _window.value.span))
    }

    fun jumpToTime(timeMillis: Long) {
        val d = data.value ?: return
        if (d.samples.size < 2) return
        jumpToIndex(d.samples.indexAt(timeMillis).coerceAtLeast(0))
    }

    fun setThreeD(on: Boolean) = _playback.update { it.copy(threeD = on) }

    fun cycleSpeed() = _playback.update { it.copy(speedIndex = (it.speedIndex + 1) % PLAY_SPEEDS.size) }

    fun togglePlay() = if (_playback.value.playing) pause() else play()

    /** Replays the ride on the map, the chart and the moments together. */
    fun play() {
        val d = data.value ?: return
        if (d.durationMillis <= 0 || d.samples.size < 2) return
        if ((_scrub.value ?: 0f) >= 0.999f) _scrub.value = 0f
        _playback.update { it.copy(playing = true) }
        playJob?.cancel()
        playJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(FRAME_MILLIS)
                val now = System.nanoTime()
                val realMillis = (now - last) / 1_000_000.0
                last = now
                val rideMillis = realMillis * PLAY_SPEEDS[_playback.value.speedIndex]
                val next = ((_scrub.value ?: 0f) + (rideMillis / d.durationMillis).toFloat()).coerceAtMost(1f)
                scrubTo(next)
                if (next >= 1f) break
            }
            _playback.update { it.copy(playing = false) }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        _playback.update { it.copy(playing = false) }
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
