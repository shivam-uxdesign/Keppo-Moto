package com.ridetrack.app.keepmoving

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keep moving's two choices: the toggle in Home › Before you ride (off by default; on, starting a
 * ride asks Where to? first) and the last mode picked on the plan screen.
 */
class KeepMovingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("keep-moving", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mode = MutableStateFlow(KeepMode.of(prefs.getString(MODE, null)))
    val mode: StateFlow<KeepMode> = _mode.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(ENABLED, on).apply()
    }

    fun setMode(m: KeepMode) {
        _mode.value = m
        prefs.edit().putString(MODE, m.name).apply()
    }

    private companion object {
        const val ENABLED = "enabled"
        const val MODE = "mode"
    }
}
