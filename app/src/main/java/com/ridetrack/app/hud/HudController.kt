package com.ridetrack.app.hud

import android.content.Context
import com.ridetrack.app.moments.MomentsHub
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ridetrack.app.MainActivity
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSize
import com.ridetrack.app.data.BikeRepository
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.sensors.Permissions
import kotlinx.coroutines.flow.first
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.app.ui.hud.HudControlActions
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Shows the pop-up HUD when a ride is being recorded and Ride Track is not in front,
 * and removes it when the rider returns, the ride ends, or they hide it for this ride.
 */
class HudController(
    private val context: Context,
    private val session: RideSessionManager,
    private val settings: SettingsRepository,
    private val moments: MomentsHub,
    private val bikes: BikeRepository,
) {
    private val scope = MainScope()
    private val appVisible = MutableStateFlow(true)
    /** The ride the rider hid the pop-up for; it stays hidden until that ride ends. */
    private val hiddenForRide = MutableStateFlow<String?>(null)
    private var stoppedSinceMillis: Long? = null
    /**
     * After a ride is stopped from the pop-up, it stays up between rides with its record
     * button, until the rider opens Ride Track or drags it away.
     */
    private val idle = MutableStateFlow(false)

    private val actions: HudControlActions = object : HudControlActions {
        override fun setLayout(layout: HudLayout) { scope.launch { settings.setHudLayout(layout) } }
        override fun setSize(size: HudSize) { scope.launch { settings.setHudSize(size) } }
        override fun setOpacity(percent: Int) { scope.launch { settings.setHudOpacity(percent) } }
        override fun hideForRide() = hideForCurrentRide()
        override fun openApp() = openRideTrack()
        override fun closeControls() = overlay.closeControls()
        override fun pauseRide() = session.pause()
        override fun resumeRide() = session.resume()
        override fun stopRide() {
            if (!session.state.value.isActive) return
            idle.value = true
            session.requestEnd()
            session.confirmEnd()
        }
        override fun startRide() = startFromPopup()
    }

    private val overlay: HudOverlay = HudOverlay(
        context = context,
        actions = actions,
        onHideByDrag = ::hideForCurrentRide,
        onPositionChanged = { x, y -> scope.launch { settings.setHudPosition(x, y) } },
    )

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> appVisible.value = true
                    Lifecycle.Event.ON_STOP -> appVisible.value = false
                    else -> Unit
                }
            },
        )

        scope.launch {
            combine(session.state, session.active, appVisible, settings.settings, combine(hiddenForRide, idle) { h, i -> h to i }) { state, active, visible, s, (hidden, waiting) ->
                overlay.settings = s.hud
                val riding = state.isActive && active != null && hidden != active.rideId
                (riding || (waiting && !state.isActive)) && !visible && s.hud.enabled
            }.distinctUntilChanged().collect { wanted ->
                if (wanted && canDrawOverlays()) {
                    refreshData()
                    if (!overlay.show()) Log.w(TAG, "Pop-up not shown")
                } else {
                    overlay.hide()
                }
            }
        }

        scope.launch {
            combine(session.frame, session.state, session.active, moments.state, combine(session.manuallyPaused, idle) { _, _ -> Unit }) { _, _, _, _, _ -> Unit }
                .collect { refreshData() }
        }

        scope.launch {
            // Reset per-ride state when a ride ends.
            session.state.collect { if (!it.isActive && it !is RideState.Saving) hiddenForRide.value = null }
        }

        scope.launch {
            // Back in Ride Track: the waiting pop-up has done its job.
            appVisible.collect { if (it) idle.value = false }
        }
    }

    private fun refreshData() {
        val state = session.state.value
        if (!state.isActive && idle.value) {
            overlay.data = HudData.idle()
            return
        }
        val paused = state is RideState.Paused || (state is RideState.EndingRide && state.wasPaused)
        val now = System.currentTimeMillis()
        stoppedSinceMillis = if (paused) stoppedSinceMillis ?: now else null
        overlay.data = HudData.from(
            frame = session.frame.value,
            active = session.active.value,
            paused = paused,
            stoppedForMillis = stoppedSinceMillis?.let { now - it },
            camera = CameraIndicator.from(moments.state.value),
            manuallyPaused = session.manuallyPaused.value,
        )
    }

    private fun hideForCurrentRide() {
        idle.value = false
        hiddenForRide.value = session.active.value?.rideId
        overlay.hide()
    }

    /** Record button: a new ride on the selected bike, straight from the pop-up. */
    private fun startFromPopup() {
        scope.launch {
            val s = settings.settings.first()
            val bike = s.selectedBikeId?.let { bikes.get(it) } ?: bikes.observeBikes().first().firstOrNull()
            // No bike yet, or location not allowed: that needs the app.
            if (bike == null || (!s.demoMode && !Permissions.hasFineLocation(context))) {
                openRideTrack()
                return@launch
            }
            if (session.startNow(bike) == null && !session.state.value.isActive) openRideTrack()
        }
    }

    private fun openRideTrack() {
        overlay.closeControls()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "Could not open app", it) }
    }

    private fun canDrawOverlays(): Boolean = OverlayPermission.isGranted(context)

    companion object {
        private const val TAG = "HudController"
    }
}
