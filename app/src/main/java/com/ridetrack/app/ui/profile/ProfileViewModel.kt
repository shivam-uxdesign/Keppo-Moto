package com.ridetrack.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.LiveMetric
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
)

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ProfileUiState> = combine(c.rides.observeCompleted(), c.settings.settings, c.session.state) { rides, settings, rideState ->
        ProfileUiState(
            loading = false,
            totals = RideTotals.from(rides).takeIf { it.rideCount > 0 },
            settings = settings,
            rideActive = rideState.isActive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

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
