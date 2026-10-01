package com.ridetrack.app.ui.profile

import androidx.lifecycle.ViewModel
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.data.SafetySettings
import com.ridetrack.app.safety.CrashReport
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.LiveMetric
import com.ridetrack.app.data.MomentSettings
import kotlinx.coroutines.flow.MutableStateFlow
import com.ridetrack.app.data.Settings
import com.ridetrack.app.ui.common.RideTotals
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val totals: RideTotals? = null,
    val settings: Settings = Settings(),
    val rideActive: Boolean = false,
    val momentsBytes: Long? = null,
)

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private val momentsBytes = MutableStateFlow<Long?>(null)

    val state: StateFlow<ProfileUiState> = combine(c.rides.observeCompleted(), c.settings.settings, c.session.state, momentsBytes) { rides, settings, rideState, bytes ->
        ProfileUiState(
            momentsBytes = bytes,
            loading = false,
            totals = RideTotals.from(rides).takeIf { it.rideCount > 0 },
            settings = settings,
            rideActive = rideState.isActive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun refreshMomentsStorage() {
        viewModelScope.launch { momentsBytes.value = c.moments.bytesUsed() }
    }

    fun setMoments(m: MomentSettings) {
        viewModelScope.launch { if (!state.value.rideActive) c.settings.setMoments(m) }
    }

    /** Debug: only while a ride with Moments is recording. */
    val canTestClip: Boolean get() = c.session.active.value?.moments != null

    fun testClip() {
        c.session.active.value?.takeIf { it.moments != null }?.let { c.momentsHub.requestTestClip(it.rideId) }
    }

    fun deleteAllMoments() {
        viewModelScope.launch {
            c.moments.deleteAll()
            refreshMomentsStorage()
        }
    }

    fun setSafety(v: SafetySettings) {
        viewModelScope.launch { c.settings.setSafety(v) }
    }

    /** Sends the "(test)" alert; [done] gets how many texts went out. */
    fun sendTestAlert(done: (Int) -> Unit) {
        viewModelScope.launch { done(c.crashAlerts.sendTest()) }
    }

    /** Debug builds: run the whole alert without a crash (texts only go out if you let it count down). */
    fun simulateCrash() {
        val f = c.session.frame.value
        c.crashAlerts.onCrash(
            CrashReport(
                timeMillis = System.currentTimeMillis(), latitude = f?.latitude, longitude = f?.longitude, accuracyM = f?.gpsAccuracyM,
                speedBeforeMps = 15.0, bikeName = c.session.active.value?.bikeName ?: "Test bike", riderName = "", batteryPercent = null, medical = MedicalInfo(),
            ),
        )
    }

    fun canSendSms(): Boolean = c.crashAlerts.canSendSms()

    fun setAutoPause(v: Boolean) {
        viewModelScope.launch { c.settings.setAutoPause(v) }
    }

    /** Demo mode can't change mid-ride, so a ride's data source never switches. */
    fun setDemoMode(v: Boolean) {
        viewModelScope.launch { if (!state.value.rideActive) c.settings.setDemoMode(v) }
    }

    fun setDemoObd(v: Boolean) {
        viewModelScope.launch { if (!state.value.rideActive) c.settings.setDemoObd(v) }
    }

    fun setGIndicator(v: Boolean) {
        viewModelScope.launch { c.settings.setGForceIndicator(v) }
    }

    fun toggleMetric(metric: LiveMetric) {
        viewModelScope.launch {
            val current = state.value.settings.liveMetrics
            val next = if (metric in current) current - metric else current + metric
            if (next.size <= LiveMetric.MAX_VISIBLE) c.settings.setLiveMetrics(next)
        }
    }
}
