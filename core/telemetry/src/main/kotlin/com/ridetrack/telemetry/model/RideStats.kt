package com.ridetrack.telemetry.model

/**
 * Aggregated ride statistics. Nullable values are *unknown* (never recorded), which is
 * different from zero.
 */
data class RideStats(
    val distanceM: Double = 0.0,
    val movingMillis: Long = 0,
    val stoppedMillis: Long = 0,
    /** Time on breaks (long stops off the bike); not part of [movingMillis] or [stoppedMillis]. */
    val breakMillis: Long = 0,
    val maxSpeedMps: Double? = null,
    val maxAccelG: Double? = null,
    /** Most negative longitudinal G (stored as a negative number). */
    val maxBrakeG: Double? = null,
    val peakG: Double? = null,
    /** Stored as positive degrees. */
    val maxLeftLeanDeg: Double? = null,
    val maxRightLeanDeg: Double? = null,
    val avgLeanDeg: Double? = null,
    val stopCount: Int = 0,
    val leftTurns: Int = 0,
    val rightTurns: Int = 0,
    val brakeEvents: Int = 0,
    val accelEvents: Int = 0,
    val leanEvents: Int = 0,
) {
    /** Average moving speed; unknown until the bike has moved. */
    val avgSpeedMps: Double?
        get() = if (movingMillis >= 1_000 && distanceM > 0) distanceM / (movingMillis / 1000.0) else null

    val avgStopMillis: Long?
        get() = if (stopCount > 0) stoppedMillis / stopCount else null
}
