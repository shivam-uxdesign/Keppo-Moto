package com.ridetrack.app.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.LiveMetric
import com.ridetrack.app.ride.ActiveRide
import com.ridetrack.app.moments.MomentState
import com.ridetrack.app.sensors.BatteryState
import com.ridetrack.app.sensors.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import com.ridetrack.telemetry.model.TelemetryFrame
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LiveChrome(
    val rideState: RideState = RideState.Idle,
    val active: ActiveRide? = null,
    val metrics: List<LiveMetric> = emptyList(),
    val showGIndicator: Boolean = false,
    val autoPause: Boolean = true,
    val battery: BatteryState? = null,
    val hudEnabled: Boolean = false,
    val hudPromptDismissed: Boolean = true,
    val location: LocationEnv = LocationEnv(),
    val moments: MomentState = MomentState(),
    /** Time left on the battery, and a warning when it's time to charge. */
    val batteryOutlook: com.ridetrack.app.ride.BatteryOutlook? = null,
)

/** Whether the phone can deliver GPS at all right now (independent of signal). */
data class LocationEnv(val permission: Boolean = true, val enabled: Boolean = true)

class LiveRideViewModel(private val c: AppContainer) : ViewModel() {
    /** Changes rarely: state machine, settings, battery. */
    private val location = MutableStateFlow(readLocation())

    val chrome: StateFlow<LiveChrome> = combine(
        c.session.state,
        c.session.active,
        c.settings.settings,
        c.battery.observe(),
        location,
        c.momentsHub.state,
        c.batteryWatch.outlook,
    ) { values ->
        val state = values[0] as RideState
        val active = values[1] as ActiveRide?
        val settings = values[2] as com.ridetrack.app.data.Settings
        val battery = values[3] as BatteryState?
        val loc = values[4] as LocationEnv
        val moments = values[5] as MomentState
        LiveChrome(
            rideState = state,
            active = active,
            metrics = LiveMetric.entries.filter { it in settings.liveMetrics }.take(LiveMetric.MAX_VISIBLE),
            showGIndicator = settings.showGForceIndicator,
            autoPause = settings.autoPause,
            battery = battery,
            hudEnabled = settings.hud.enabled,
            hudPromptDismissed = settings.hud.promptDismissed,
            location = loc,
            moments = moments,
            batteryOutlook = values[6] as com.ridetrack.app.ride.BatteryOutlook?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveChrome(rideState = c.session.state.value, active = c.session.active.value))

    /** 5 Hz telemetry snapshot. */
    val frame: StateFlow<TelemetryFrame?> = c.session.frame

    init {
        // Location can be switched off/on from quick settings mid-ride; notice it promptly.
        viewModelScope.launch {
            while (isActive) {
                refreshLocation()
                delay(3_000)
            }
        }
    }

    private fun readLocation() = LocationEnv(
        permission = Permissions.hasFineLocation(c.appContext),
        enabled = c.sensorInventory.isGpsEnabled(),
    )

    /** Re-reads location state; restarts GPS automatically when it just became usable. */
    fun refreshLocation() {
        val before = location.value
        val now = readLocation()
        location.value = now
        val usable = now.permission && now.enabled
        if (usable && !(before.permission && before.enabled)) c.session.retryGps()
    }

    fun retryGps() {
        refreshLocation()
        c.session.retryGps()
    }

    fun calibrateNow() = c.session.calibrateNow()

    /** The rider paused the ride (here or from the pop-up). */
    val manuallyPaused: StateFlow<Boolean> = c.session.manuallyPaused
    fun togglePause() = if (c.session.manuallyPaused.value) c.session.resume() else c.session.pause()

    fun requestEnd() = c.session.requestEnd()
    fun cancelEnd() = c.session.cancelEnd()
    fun confirmEnd() = c.session.confirmEnd()

    /** Hold-to-end completed: the hold itself is the confirmation. */
    fun endNow() {
        c.session.requestEnd()
        c.session.confirmEnd()
    }
    fun acknowledge() = c.session.acknowledge()

    fun dismissHudPrompt() {
        viewModelScope.launch { c.settings.setHudPromptDismissed(true) }
    }
}
