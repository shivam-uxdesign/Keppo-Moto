package com.ridetrack.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.ride.RideNames
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.common.RideTotals
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val environment = MutableStateFlow(readEnvironment())
    private val starting = MutableStateFlow(false)

    val state: StateFlow<HomeUiState> = combine(
        c.bikes.observeBikes(),
        c.settings.settings,
        c.rides.observeCompleted(),
        combine(c.rides.observeInProgress(), c.session.active, c.session.state) { inProgress, active, rideState ->
            Triple(inProgress.firstOrNull { it.id != active?.rideId }, rideState, active)
        },
        combine(environment, starting) { e, s -> e to s },
    ) { bikes, settings, rides, (unfinished, rideState, _), (env, isStarting) ->
        val bike = bikes.firstOrNull { it.id == settings.selectedBikeId } ?: bikes.firstOrNull()
        val totals = RideTotals.from(rides)
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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
        viewModelScope.launch { c.rides.delete(ride.id) }
    }
}
