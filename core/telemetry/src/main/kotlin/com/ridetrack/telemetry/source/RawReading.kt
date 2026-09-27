package com.ridetrack.telemetry.source

/**
 * Raw, unprocessed data from a [TelemetrySource]. Timestamps are monotonic nanoseconds
 * (on Android: the elapsed-realtime clock used by both sensors and locations).
 */
sealed interface RawReading {
    val timeNanos: Long
}

data class LocationReading(
    override val timeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val horizontalAccuracyM: Double?,
) : RawReading

/** Specific force in m/s², including gravity, in the phone frame. */
data class AccelReading(override val timeNanos: Long, val x: Double, val y: Double, val z: Double) : RawReading

/** Angular velocity in rad/s in the phone frame. */
data class GyroReading(override val timeNanos: Long, val x: Double, val y: Double, val z: Double) : RawReading

/**
 * Engine data from a vehicle link (Bluetooth OBD). [rpm] and [gear] are independently
 * optional: many bikes report RPM but not the gear. Gear 0 = neutral.
 */
data class EngineReading(override val timeNanos: Long, val rpm: Double?, val gear: Int?) : RawReading

enum class SourceSignal {
    GPS_PROVIDER_DISABLED,
    GPS_PROVIDER_ENABLED,
    MOTION_SENSOR_UNRELIABLE,
    MOTION_SENSOR_RELIABLE,
}

data class SourceStatusReading(override val timeNanos: Long, val signal: SourceSignal) : RawReading
