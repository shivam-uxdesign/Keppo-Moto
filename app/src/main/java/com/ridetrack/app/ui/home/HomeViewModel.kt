package com.ridetrack.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.ride.RideNames
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.common.Odometer
import com.ridetrack.app.ui.common.RideTotals
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class GpsReadiness { READY, PERMISSION_NEEDED, DISABLED, NO_HARDWARE }

data class HomeUiState(
    val loading: Boolean = true,
    val bike: Bike? = null,
    val bikes: List<Bike> = emptyList(),
    val hasBikes: Boolean = false,
    val starting: Boolean = false,
    val demoMode: Boolean = false,
    val rideState: RideState = RideState.Idle,
    val totals: RideTotals? = null,
    val unfinished: Ride? = null,
    val gps: GpsReadiness = GpsReadiness.READY,
    val sensors: SensorAvailability = SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = true),
    val stats: HomeStats? = null,
    /** Current odometer per bike id, in km; null = not set. */
    val odometers: Map<String, Double?> = emptyMap(),
    /** "Ridden today" etc. per bike id. */
    val lastRidden: Map<String, String> = emptyMap(),
    /** Speed trace of the latest real ride, for the Today chart. */
    val trace: SpeedTrace? = null,
    /** Lifetime numbers per bike id, for the back of the card. */
    val bikeStats: Map<String, BikeStats> = emptyMap(),
)

private data class Extras(
    val environment: Pair<GpsReadiness, SensorAvailability>,
    val starting: Boolean,
    val trace: SpeedTrace?,
    val momentCounts: Map<String, Int>,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val environment = MutableStateFlow(readEnvironment())
    private val starting = MutableStateFlow(false)

    /** The latest real ride's speed trace, loaded off the main thread when that ride changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val trace = c.rides.observeCompleted()
        .map { rides -> rides.filter { it.source != DataSourceKind.DEMO }.maxByOrNull { it.startTimeMillis } }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .mapLatest { ride ->
            ride?.let {
                val zone = ZoneId.systemDefault()
                val isToday = Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate() == LocalDate.now(zone)
                SpeedTrace.from(c.rides.track(it.id).samples, TRACE_POINTS, isToday)
            }
        }
        .flowOn(Dispatchers.IO)
        .onStart { emit(null) }

    val state: StateFlow<HomeUiState> = combine(
        c.bikes.observeBikes(),
        c.settings.settings,
        c.rides.observeCompleted(),
        combine(c.rides.observeInProgress(), c.session.active, c.session.state) { inProgress, active, rideState ->
            Triple(inProgress.firstOrNull { it.id != active?.rideId }, rideState, active)
        },
        combine(environment, starting, trace, c.moments.observeCountsByBike()) { e, s, t, m -> Extras(e, s, t, m) },
    ) { bikes, settings, rides, (unfinished, rideState, _), (env, isStarting, speedTrace, momentCounts) ->
        val bike = bikes.firstOrNull { it.id == settings.selectedBikeId } ?: bikes.firstOrNull()
        val totals = RideTotals.from(rides)
        val now = ZonedDateTime.now()
        val real = rides.filter { it.source != DataSourceKind.DEMO }
        HomeUiState(
            loading = false,
            bike = bike,
            bikes = bikes,
            starting = isStarting,
            hasBikes = bikes.isNotEmpty(),
            demoMode = settings.demoMode,
            rideState = rideState,
            totals = totals.takeIf { it.rideCount > 0 },
            unfinished = unfinished,
            gps = env.first,
            sensors = env.second,
            stats = HomeStats.from(rides, now),
            odometers = bikes.associate { it.id to Odometer.readingKm(it, rides) },
            lastRidden = bikes.associate { b -> b.id to lastRiddenLabel(real.filter { it.bikeId == b.id }.maxOfOrNull { it.startTimeMillis }, now) },
            trace = speedTrace,
            bikeStats = bikes.associate { b -> b.id to BikeStats.from(b, rides, momentCounts[b.id] ?: 0) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun selectBike(id: String) {
        viewModelScope.launch { c.settings.setSelectedBike(id) }
    }

    /** Call when returning to the screen: permissions/GPS may have changed in Settings. */
    fun refreshEnvironment() {
        environment.value = readEnvironment()
    }

    private fun readEnvironment(): Pair<GpsReadiness, SensorAvailability> {
        val inv = c.sensorInventory
        val gps = when {
            !inv.hasGpsHardware() -> GpsReadiness.NO_HARDWARE
            !Permissions.hasFineLocation(c.appContext) -> GpsReadiness.PERMISSION_NEEDED
            !inv.isGpsEnabled() -> GpsReadiness.DISABLED
            else -> GpsReadiness.READY
        }
        return gps to inv.availability()
    }

    /** Starts recording immediately on [bike]; GPS status is handled on the live screen. */
    fun startRide(bike: Bike, onStarted: () -> Unit) {
        if (starting.value) return
        starting.value = true
        viewModelScope.launch {
            c.settings.setSelectedBike(bike.id)
            val id = c.session.startNow(bike)
            starting.value = false
            if (id != null || c.session.state.value.isActive) onStarted()
        }
    }

    fun saveUnfinished(ride: Ride) {
        viewModelScope.launch { c.rides.recover(ride, RideNames.forStart(ride.startTimeMillis)) }
    }

    fun discardUnfinished(ride: Ride) {
        viewModelScope.launch {
            c.rides.delete(ride.id)
            c.moments.deleteFilesForRide(ride.id)
        }
    }

    private companion object {
        const val TRACE_POINTS = 48
    }
}
