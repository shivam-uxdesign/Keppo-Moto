package com.ridetrack.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.RideTrack
import com.ridetrack.app.ui.common.indexAt
import com.ridetrack.app.ui.common.positionAt
import com.ridetrack.app.ui.common.positionAtSmooth
import com.ridetrack.app.ui.common.sampleAt
import com.ridetrack.app.moments.Moment
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

    /** Ride time (ms, between samples) at [fraction] of the timeline. */
    fun timeAt(fraction: Float): Double {
        if (samples.size < 2) return startMillis.toDouble()
        val x = fraction.coerceIn(0f, 1f).toDouble() * (samples.size - 1)
        val i = x.toInt().coerceAtMost(samples.size - 2)
        val a = samples[i].timeMillis
        return a + (samples[i + 1].timeMillis - a) * (x - i)
    }

    /** Where ride time [timeMillis] sits on the timeline (0..1), between samples. */
    fun fractionOf(timeMillis: Double): Float {
        if (samples.size < 2) return 0f
        val i = samples.indexAt(timeMillis.toLong()).coerceAtLeast(0)
        if (i >= samples.size - 1) return 1f
        val a = samples[i].timeMillis
        val b = samples[i + 1].timeMillis
        val within = if (b > a) ((timeMillis - a) / (b - a)).coerceIn(0.0, 1.0) else 0.0
        return ((i + within) / (samples.size - 1)).toFloat()
    }

    fun timeOf(index: Int): Double = samples.getOrNull(index)?.timeMillis?.toDouble() ?: startMillis.toDouble()

    /** Direction of travel at [timeMillis], from the smoothed positions either side. */
    fun bearingAtTime(timeMillis: Double): Float? {
        val a = samples.positionAtSmooth(timeMillis - SMOOTH_BEARING_SPAN_MS) ?: return null
        val b = samples.positionAtSmooth(timeMillis + SMOOTH_BEARING_SPAN_MS) ?: return null
        if (Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude) < 3.0) return samples.sampleAt(timeMillis.toLong())?.headingDeg?.toFloat()
        return Geo.bearingDeg(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()
    }

    private fun series(symmetric: Boolean = false, floorZero: Boolean = false, f: (TelemetrySample) -> Double?) =
        ChartSeries(FloatArray(samples.size) { i -> f(samples[i])?.toFloat() ?: Float.NaN }, symmetric, floorZero)
}

private const val BEARING_SPAN_MS = 4_000L
private const val SMOOTH_BEARING_SPAN_MS = 8_000.0

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
private const val JUMP_WINDOW_MS = 10 * 60_000L
private const val JUMP_MIN_SPAN_MS = 60_000L

class RideDetailViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val data = MutableStateFlow<TrackData?>(null)
    /** The playhead as ride time (ms); moves continuously during replay. */
    private val _time = MutableStateFlow<Double?>(null)
    val time: StateFlow<Double?> = _time.asStateFlow()
    private val _scrub = MutableStateFlow<Float?>(null)
    val scrub: StateFlow<Float?> = _scrub.asStateFlow()
    private val _window = MutableStateFlow(ChartWindow.Full)
    val window: StateFlow<ChartWindow> = _window.asStateFlow()
    private val _playback = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = _playback.asStateFlow()

    val moments: StateFlow<List<Moment>> = c.moments.observe(rideId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val replay = MomentReplay()
    private val _activeMoment = MutableStateFlow<Moment?>(null)
    /** The moment popping up over the map during replay. */
    val activeMoment: StateFlow<Moment?> = _activeMoment.asStateFlow()

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
            if (_time.value == null && d.samples.size >= 2) setTime(d.timeOf(d.initialIndex))
        }
        viewModelScope.launch {
            moments.collect { list -> replay.setWindows(list.map(MomentWindow::of)) }
        }
    }

    private fun setTime(t: Double, follow: Boolean = true) {
        val d = data.value ?: return
        val clamped = t.coerceIn(d.startMillis.toDouble(), (d.startMillis + d.durationMillis).toDouble())
        _time.value = clamped
        val f = d.fractionOf(clamped)
        _scrub.value = f
        if (follow) _window.value = _window.value.follow(f)
    }

    /** The rider moved the playhead: moments may pop up again. */
    private fun moved() {
        replay.reset()
        _activeMoment.value = null
    }

    fun scrubTo(fraction: Float) {
        val d = data.value ?: return
        moved()
        setTime(d.timeAt(fraction))
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
        jumpToTime(d.timeOf(index))
    }

    fun jumpToTime(timeMillis: Long) = jumpToTime(timeMillis.toDouble())

    private fun jumpToTime(timeMillis: Double) {
        val d = data.value ?: return
        if (d.samples.size < 2) return
        pause()
        moved()
        setTime(timeMillis, follow = false)
        val f = _scrub.value ?: return
        val around = d.durationMillis.takeIf { it > 0 }?.let { (JUMP_WINDOW_MS.toFloat() / it).coerceAtMost(1f) } ?: 1f
        _window.value = ChartWindow.around(f, minOf(around, _window.value.span))
    }

    fun setThreeD(on: Boolean) = _playback.update { it.copy(threeD = on) }

    fun cycleSpeed() = _playback.update { it.copy(speedIndex = (it.speedIndex + 1) % PLAY_SPEEDS.size) }

    fun togglePlay() = if (_playback.value.playing) pause() else play()

    /** Replays the ride on the map, the chart and the moments together; the screen drives it with [advance]. */
    fun play() {
        val d = data.value ?: return
        if (d.durationMillis <= 0 || d.samples.size < 2) return
        if ((_scrub.value ?: 0f) >= 0.999f) {
            moved()
            setTime(d.startMillis.toDouble())
        }
        _playback.update { it.copy(playing = true) }
    }

    /** One display frame of replay: [realMillis] of wall-clock time has passed. */
    fun advance(realMillis: Double) {
        val d = data.value ?: return
        if (!_playback.value.playing) return
        val now = _time.value ?: d.startMillis.toDouble()
        val next = replay.advance(now, realMillis, PLAY_SPEEDS[_playback.value.speedIndex])
        setTime(next)
        syncActive()
        if (next >= d.startMillis + d.durationMillis) pause()
    }

    /** Tapped outside the pop-up: skip the rest of the moment and carry on. */
    fun skipMoment() {
        val end = replay.skip() ?: return
        setTime(end)
        syncActive()
    }

    private fun syncActive() {
        val id = replay.active?.id
        if (_activeMoment.value?.id != id) _activeMoment.value = id?.let { i -> moments.value.firstOrNull { it.id == i } }
    }

    fun pause() {
        _playback.update { it.copy(playing = false) }
    }

    private val _chart = MutableStateFlow(ChartKind.SPEED)
    val chart: StateFlow<ChartKind> = _chart.asStateFlow()
    fun selectChart(kind: ChartKind) {
        _chart.value = kind
    }

    fun cycleChart() {
        _chart.value = ChartKind.entries[(_chart.value.ordinal + 1) % ChartKind.entries.size]
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
