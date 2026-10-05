package com.ridetrack.telemetry.model

/** Derived, UI-facing snapshot of the ride. Emitted at a low, fixed rate. */
data class TelemetryFrame(
    val timeMillis: Long,
    val elapsedMillis: Long,
    val stats: RideStats,
    val speedMps: Double?,
    val leanDeg: Double?,
    val leanConfidence: LeanConfidence,
    val longitudinalG: Double?,
    val lateralG: Double?,
    val headingDeg: Double?,
    val altitudeM: Double?,
    val gpsQuality: GpsQuality,
    val gpsAccuracyM: Double?,
    val isStopped: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val source: DataSourceKind,
    /** Engine speed from OBD; null when no engine link is connected or data is stale. */
    val rpm: Double? = null,
    /** Current gear from OBD (0 = neutral); null when the bike doesn't report it. */
    val gear: Int? = null,
    val calibration: CalibrationInfo = CalibrationInfo(),
    /** On a break (a long stop off the bike), since [breakStartMillis]. */
    val onBreak: Boolean = false,
    val breakStartMillis: Long? = null,
    /** The phone is tilted far from its mount (pocket, hand): lean and photos aren't meaningful. */
    val offMount: Boolean = false,
) {
    val combinedG: Double?
        get() = if (longitudinalG != null && lateralG != null) kotlin.math.hypot(longitudinalG, lateralG) else null
}
