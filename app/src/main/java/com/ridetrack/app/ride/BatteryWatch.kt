package com.ridetrack.app.ride

import android.content.Context
import com.ridetrack.app.moments.MomentLog
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.app.sensors.BatteryMonitor
import com.ridetrack.telemetry.processing.BatteryForecast
import com.ridetrack.telemetry.processing.BatteryWarnings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** The battery during a ride: now, and how long it will last with the current settings. */
data class BatteryOutlook(val percent: Int, val charging: Boolean, val minutesLeft: Int?, val warning: String?)

/**
 * Samples the battery every minute during a ride: logs it (with temperature and the drain) in
 * the ride's moments log, estimates the time left, and raises a warning when it's time to
 * charge. Never stops anything. Learns each rider's drain (Moments on / off) for the next ride.
 */
class BatteryWatch(
    context: Context,
    private val battery: BatteryMonitor,
    private val moments: MomentRepository,
) {
    private val prefs = context.getSharedPreferences("battery", Context.MODE_PRIVATE)
    private val _outlook = MutableStateFlow<BatteryOutlook?>(null)
    val outlook: StateFlow<BatteryOutlook?> = _outlook.asStateFlow()
    private var job: Job? = null

    /** Past rides' drain (% per hour, not charging), with Moments on or off; null = none yet. */
    fun learnedPerHour(momentsOn: Boolean): Double? =
        prefs.getFloat(key(momentsOn), -1f).takeIf { it > 0f }?.toDouble()

    fun start(rideId: String, momentsOn: Boolean, scope: CoroutineScope, onWarning: (String) -> Unit) {
        job?.cancel()
        val forecast = BatteryForecast()
        val warnings = BatteryWarnings()
        val log = File(moments.dir(rideId), MomentLog.FILE_NAME)
        var firstOff: Pair<Long, Int>? = null
        var lastOff: Pair<Long, Int>? = null
        job = scope.launch {
            try {
                while (isActive) {
                    battery.current()?.let { b ->
                        val now = System.currentTimeMillis()
                        forecast.add(now, b.percent, b.charging)
                        if (!b.charging) {
                            if (firstOff == null) firstOff = now to b.percent
                            lastOff = now to b.percent
                        } else {
                            firstOff = null
                        }
                        val left = forecast.minutesLeft(b.percent, if (b.charging) null else learnedPerHour(momentsOn))
                        val warning = warnings.check(b.percent, b.charging, left)
                        _outlook.value = BatteryOutlook(b.percent, b.charging, left, warning ?: _outlook.value?.warning)
                        MomentLog.append(
                            log,
                            "battery: ${b.percent} % · ${if (b.charging) "charging" else "not charging"}" +
                                (b.temperatureC?.let { " · ${String.format(Locale.US, "%.1f", it)} °C" } ?: "") +
                                (forecast.drainPerHour()?.let { " · ${String.format(Locale.US, "%.1f", it)} %/h" } ?: "") +
                                (left?.let { " · about ${BatteryWarnings.duration(it)} left" } ?: ""),
                        )
                        if (warning != null) {
                            MomentLog.append(log, "battery warning: $warning")
                            onWarning(warning)
                        }
                    }
                    delay(SAMPLE_MILLIS)
                }
            } finally {
                // Learn this ride's drain for next time, if it ran long enough off the charger.
                val a = firstOff
                val z = lastOff
                if (a != null && z != null && z.first - a.first >= LEARN_AFTER_MILLIS && a.second > z.second) {
                    val rate = (a.second - z.second) / ((z.first - a.first) / 3_600_000.0)
                    val old = learnedPerHour(momentsOn)
                    prefs.edit().putFloat(key(momentsOn), (if (old == null) rate else (old + rate) / 2).toFloat()).apply()
                }
                _outlook.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun key(momentsOn: Boolean) = if (momentsOn) "drain_moments" else "drain_plain"

    private companion object {
        const val SAMPLE_MILLIS = 60_000L
        const val LEARN_AFTER_MILLIS = 15 * 60_000L
    }
}
