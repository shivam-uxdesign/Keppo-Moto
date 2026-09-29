package com.ridetrack.telemetry.moments

import com.ridetrack.telemetry.model.TelemetrySample

/** Ride values at one instant, interpolated between the 1 Hz samples. Null = unknown. */
data class TelemetryPoint(
    val timeMillis: Long,
    val speedMps: Double?,
    val leanDeg: Double?,
    val longitudinalG: Double?,
    val lateralG: Double?,
    val latitude: Double?,
    val longitude: Double?,
) {
    val combinedG: Double?
        get() = if (longitudinalG != null && lateralG != null) kotlin.math.hypot(longitudinalG, lateralG) else longitudinalG?.let { kotlin.math.abs(it) }
}

/**
 * Linear interpolation over time-sorted [samples]. A value is interpolated only when both
 * neighbours know it; otherwise the nearer one is used if it's within [maxGapMillis].
 * Never invents data outside the ride.
 */
fun telemetryAt(samples: List<TelemetrySample>, timeMillis: Long, maxGapMillis: Long = 1_500): TelemetryPoint? {
    if (samples.isEmpty()) return null
    if (timeMillis < samples.first().timeMillis - maxGapMillis || timeMillis > samples.last().timeMillis + maxGapMillis) return null
    var hi = samples.indexOfFirst { it.timeMillis >= timeMillis }
    if (hi < 0) hi = samples.size - 1
    val lo = if (samples[hi].timeMillis > timeMillis && hi > 0) hi - 1 else hi
    val a = samples[lo]
    val b = samples[hi]
    val span = (b.timeMillis - a.timeMillis).toDouble()
    val f = if (span <= 0) 0.0 else ((timeMillis - a.timeMillis) / span).coerceIn(0.0, 1.0)

    fun pick(va: Double?, vb: Double?): Double? = when {
        va != null && vb != null && span <= 5_000 -> va + (vb - va) * f
        // Otherwise: the nearest neighbour that knows the value, if it's close enough.
        else -> listOf(a to va, b to vb)
            .filter { it.second != null }
            .minByOrNull { kotlin.math.abs(it.first.timeMillis - timeMillis) }
            ?.takeIf { kotlin.math.abs(it.first.timeMillis - timeMillis) <= maxGapMillis }
            ?.second
    }
    return TelemetryPoint(
        timeMillis = timeMillis,
        speedMps = pick(a.speedMps, b.speedMps),
        leanDeg = pick(a.leanDeg, b.leanDeg),
        longitudinalG = pick(a.longitudinalG, b.longitudinalG),
        lateralG = pick(a.lateralG, b.lateralG),
        latitude = pick(a.latitude, b.latitude),
        longitude = pick(a.longitude, b.longitude),
    )
}
