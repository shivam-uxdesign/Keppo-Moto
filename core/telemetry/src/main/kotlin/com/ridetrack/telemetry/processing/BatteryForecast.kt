package com.ridetrack.telemetry.processing

import kotlin.math.roundToInt

/**
 * How long the phone will last with the current settings, from how fast the battery has
 * dropped over the last [windowMillis] (a straight-line fit). Before there's enough of this
 * ride, [fallbackPerHour] (the rider's past rides) stands in. Pure.
 */
class BatteryForecast(
    private val windowMillis: Long = 10 * 60_000L,
    private val minSpanMillis: Long = 4 * 60_000L,
) {
    private val samples = ArrayDeque<Triple<Long, Int, Boolean>>() // time, %, charging

    fun add(timeMillis: Long, percent: Int, charging: Boolean) {
        // Plugging in or out changes the slope: start measuring again.
        if (samples.isNotEmpty() && samples.last().third != charging) samples.clear()
        samples.addLast(Triple(timeMillis, percent, charging))
        while (samples.size > 2 && timeMillis - samples.first().first > windowMillis) samples.removeFirst()
    }

    /** % lost per hour over the window (negative = gaining); null until it spans [minSpanMillis]. */
    fun drainPerHour(): Double? {
        if (samples.size < 2 || samples.last().first - samples.first().first < minSpanMillis) return null
        val t0 = samples.first().first
        val xs = samples.map { (it.first - t0) / 3_600_000.0 }
        val ys = samples.map { it.second.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return -slope
    }

    /** Minutes until empty at [percent]; null when it isn't dropping (or nothing to go on). */
    fun minutesLeft(percent: Int, fallbackPerHour: Double?): Int? {
        val rate = drainPerHour() ?: fallbackPerHour ?: return null
        if (rate < MIN_DRAIN_PER_HOUR) return null
        return (percent / rate * 60).roundToInt()
    }

    companion object {
        /** Slower than this is noise (or a charger keeping up). */
        const val MIN_DRAIN_PER_HOUR = 1.0
    }
}

/**
 * When to tell the rider: below 30 % and again below 15 % (not charging), when the estimate
 * drops under 20 min, and once if a charger isn't keeping up. Each fires once per ride. Pure.
 */
class BatteryWarnings {
    private val said = HashSet<String>()

    /** The warning to show now, or null. */
    fun check(percent: Int, charging: Boolean, minutesLeft: Int?): String? {
        val left = minutesLeft?.let { " With these settings your phone will last about ${duration(it)}." } ?: ""
        val key = when {
            charging && minutesLeft != null -> "weak"
            charging -> return null
            minutesLeft != null && minutesLeft < 20 -> "20min"
            percent <= 15 -> "15"
            percent <= 30 -> "30"
            else -> return null
        }
        if (!said.add(key)) return null
        if (key == "15") said += "30"
        return when (key) {
            "weak" -> "Charging, but the battery is still dropping: about ${duration(minutesLeft!!)} left."
            "20min" -> "Battery $percent %.$left Charge it now."
            else -> "Battery $percent %.$left Charge before then."
        }
    }

    companion object {
        fun duration(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
}
