package com.ridetrack.app.studio

import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The ride's numbers as Studio shows them, from its samples. Pure, unit-tested. */
object StudioNumbers {
    data class Summary(val distanceM: Double, val movingMs: Long, val topKmh: Double)

    /** km/h at [wall], from the nearest sample within 3 s; -1 when there's none (a GPS gap). */
    fun speedAt(samples: List<TelemetrySample>, wall: Long): Int {
        if (samples.isEmpty()) return -1
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) { val mid = (lo + hi) / 2; if (samples[mid].timeMillis < wall) lo = mid + 1 else hi = mid }
        val near = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1))
            .filter { it.speedMps != null }
            .minByOrNull { kotlin.math.abs(it.timeMillis - wall) }
            ?.takeIf { kotlin.math.abs(it.timeMillis - wall) < 3_000 } ?: return -1
        return (near.speedMps!! * 3.6).roundToInt()
    }

    /**
     * Distance along the GPS points, time spent moving (gaps over 60 s don't count) and the top
     * speed (a single-sample spike is ignored: the second-highest of each 3 neighbours).
     */
    fun summary(samples: List<TelemetrySample>): Summary {
        var dist = 0.0
        var moving = 0L
        var prev: TelemetrySample? = null
        for (s in samples) {
            val p = prev
            if (p != null) {
                val dt = s.timeMillis - p.timeMillis
                if (dt in 1..60_000 && (s.speedMps ?: 0.0) > 1.0) moving += dt
                if (p.latitude != null && p.longitude != null && s.latitude != null && s.longitude != null && dt in 1..60_000) {
                    dist += meters(p.latitude!!, p.longitude!!, s.latitude!!, s.longitude!!)
                }
            }
            prev = s
        }
        val speeds = samples.mapNotNull { it.speedMps }
        val top = if (speeds.size < 3) speeds.maxOrNull() ?: 0.0
        else speeds.windowed(3).maxOf { w -> w.sorted()[1] }
        return Summary(dist, moving, top * 3.6)
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }
}
