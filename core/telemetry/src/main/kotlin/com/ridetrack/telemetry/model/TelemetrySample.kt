package com.ridetrack.telemetry.model

/** Persisted, downsampled (≈1 Hz) telemetry point. Null = not available at that time. */
data class TelemetrySample(
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val altitudeM: Double?,
    val headingDeg: Double?,
    val longitudinalG: Double?,
    val lateralG: Double?,
    val leanDeg: Double?,
    val gpsAccuracyM: Double?,
    val rpm: Double? = null,
    val gear: Int? = null,
) {
    val combinedG: Double?
        get() = if (longitudinalG != null && lateralG != null) {
            kotlin.math.hypot(longitudinalG, lateralG)
        } else {
            longitudinalG?.let { kotlin.math.abs(it) } ?: lateralG?.let { kotlin.math.abs(it) }
        }
}
