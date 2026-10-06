package com.ridetrack.app.hud

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.moments.MomentStatus
import com.ridetrack.app.moments.MomentsHub
import com.ridetrack.app.ui.format.Format
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ridetrack.app.MainActivity
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSize
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.data.VoiceSensitivity
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.app.ui.hud.HudControlActions
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Shows the pop-up HUD when a ride is being recorded and Keppo Moto is not in front,
 * and removes it when the rider returns, the ride ends, or they hide it for this ride.
 * Its record button films a video (saved as a moment); rides start and end in the app.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class HudController(
    private val context: Context,
    private val session: RideSessionManager,
    private val settings: SettingsRepository,
    private val moments: MomentsHub,
) {
    private val scope = MainScope()
    private val appVisible = MutableStateFlow(true)
    /** The ride the rider hid the pop-up for; it stays hidden until that ride ends. */
    private val hiddenForRide = MutableStateFlow<String?>(null)
    private var stoppedSinceMillis: Long? = null
    /** A short note under the buttons (why a video couldn't start), with when it was set. */
    private val note = MutableStateFlow<Pair<String, Long>?>(null)
    /** "Start filming when I speak" and its sensitivity, for the pop-up's level meter. */
    private val voice = MutableStateFlow(false to VoiceSensitivity.MEDIUM)

    private val actions: HudControlActions = object : HudControlActions {
        override fun setLayout(layout: HudLayout) { scope.launch { settings.setHudLayout(layout) } }
        override fun setSize(size: HudSize) { scope.launch { settings.setHudSize(size) } }
        override fun setOpacity(percent: Int) { scope.launch { settings.setHudOpacity(percent) } }
        override fun hideForRide() = hideForCurrentRide()
        override fun openApp() = openRideTrack()
        override fun closeControls() = overlay.closeControls()
        override fun recordVideo() {
            if (!session.startVideo()) note.value = "Turn on Moments in Keppo Moto to film" to System.currentTimeMillis()
        }
        override fun pauseVideo() = session.pauseVideo()
        override fun resumeVideo() = session.resumeVideo()
        override fun stopVideo() = session.stopVideo()
        override fun reconnectMic() {
            moments.reconnectMic()
            overlay.closeControls()
        }
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
            combine(session.state, session.active, appVisible, settings.settings, hiddenForRide) { state, active, visible, s, hidden ->
                overlay.settings = s.hud
                val riding = state.isActive && active != null && hidden != active.rideId
                riding && !visible && s.hud.enabled
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
            combine(session.frame, session.state, session.active, moments.state, combine(session.manuallyPaused, moments.live, moments.liveSaved, note) { _, _, _, _ -> Unit }) { _, _, _, _, _ -> Unit }
                .collect { refreshData() }
        }

        scope.launch {
            moments.viewfinder.collect { overlay.viewfinder = it?.asImageBitmap() }
        }

        scope.launch {
            settings.settings.map { it.moments.voice to it.moments.voiceSensitivity }.distinctUntilChanged().collect { voice.value = it }
        }

        scope.launch {
            // The level meter: ~10 updates a second is plenty to watch.
            combine(moments.micLevel.sample(100), moments.speaking, voice) { _, _, _ -> Unit }.collect { if (voice.value.first) refreshData() }
        }

        scope.launch {
            // The chosen mic dropping out (or coming back): say so once under the pop-up.
            var missing: String? = null
            moments.state.map { it.micFallback }.distinctUntilChanged().collect { name ->
                val now = System.currentTimeMillis()
                when {
                    name != null && missing == null -> note.value = "$name disconnected · recording with the phone mic" to now
                    name == null && missing != null && moments.state.value.status != MomentStatus.OFF -> note.value = "$missing back" to now
                }
                missing = name
            }
        }

        scope.launch {
            // Riding off after a break: say so briefly under the pop-up.
            var wasOnBreak = false
            session.frame.map { it?.onBreak == true }.distinctUntilChanged().collect { onBreak ->
                if (wasOnBreak && !onBreak && session.state.value.isActive) note.value = "Break over · ride resumed" to System.currentTimeMillis()
                wasOnBreak = onBreak
            }
        }

        scope.launch {
            // Reset per-ride state when a ride ends.
            session.state.collect { if (!it.isActive && it !is RideState.Saving) hiddenForRide.value = null }
        }

    }

    private fun refreshData() {
        val state = session.state.value
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
            video = moments.live.value?.let { HudVideo(it.source, it.starting, it.paused, it.elapsedMillis(now)) },
            savedNote = moments.liveSaved.value?.takeIf { now - it.atMillis < SAVED_NOTE_MILLIS }?.let { saved ->
                (if (saved.source == MomentSource.GPS_LOST) "GPS back · saved · " else "Saved to moments · ") + Format.clock(saved.lengthMillis)
            } ?: note.value?.takeIf { now - it.second < SAVED_NOTE_MILLIS }?.first,
            moments = moments.state.value,
            // Clip and photo times come from ride frames, so count on the same clock.
            nowMillis = session.frame.value?.timeMillis ?: now,
        ).copy(
            voiceOn = voice.value.first && session.active.value?.moments != null,
            micLevelDb = moments.micLevel.value,
            voiceMarginDb = voice.value.second.marginDb,
            speaking = moments.speaking.value,
        )
    }

    private fun hideForCurrentRide() {
        hiddenForRide.value = session.active.value?.rideId
        overlay.hide()
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
        private const val SAVED_NOTE_MILLIS = 2_500L
    }
}
