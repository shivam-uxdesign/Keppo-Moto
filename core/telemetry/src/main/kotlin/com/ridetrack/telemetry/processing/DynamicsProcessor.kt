package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.math.Ema
import com.ridetrack.telemetry.math.Units
import com.ridetrack.telemetry.math.Vec3
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.source.AccelReading

/**
 * Longitudinal (acceleration / braking) and lateral (cornering) G.
 *
 * Longitudinal G comes from the accelerometer projected on the bike's forward axis, with
 * a slowly-updated bias (road gradient, mount pitch) removed by comparing against GPS
 * speed changes. Without calibration it falls back to GPS-derived acceleration.
 * Lateral G is the ground-frame cornering acceleration `v·ω_yaw / g` (+ = right turn).
 */
class DynamicsProcessor(
    calibration: MountCalibration?,
    private val sensors: SensorAvailability,
) {
    /** Can change mid-ride; the bias estimate restarts with the new axes. */
    var calibration: MountCalibration? = calibration
        set(value) {
            field = value
            bias = 0.0
            sumSinceFix = 0.0
            countSinceFix = 0
            lastAccelNanos = null
        }

    private val useSensor get() = calibration != null && sensors.accelerometer

    private val longEma = Ema(0.3)
    private val gpsLongEma = Ema(0.5)
    private var lastAccelNanos: Long? = null
    private var bias = 0.0
    private var sumSinceFix = 0.0
    private var countSinceFix = 0

    var longitudinalG: Double? = null
        private set

    fun onAccel(r: AccelReading) {
        val cal = calibration ?: return
        if (!useSensor) return
        val dt = lastAccelNanos?.let { (r.timeNanos - it) / 1e9 } ?: 0.0
        lastAccelNanos = r.timeNanos
        val raw = Units.mps2ToG(Vec3(r.x, r.y, r.z) dot cal.forward)
        sumSinceFix += raw
        countSinceFix++
        longitudinalG = longEma.update(raw, dt) - bias
    }

    /** Feed each GPS-derived acceleration (m/s²), or null when unavailable. */
    fun onGpsAccel(accelMps2: Double?, dtSec: Double) {
        if (accelMps2 == null) {
            sumSinceFix = 0.0
            countSinceFix = 0
            if (!useSensor) longitudinalG = null
            return
        }
        val gpsG = Units.mps2ToG(accelMps2)
        if (useSensor) {
            if (countSinceFix > 0) {
                val err = sumSinceFix / countSinceFix - gpsG
                bias += 0.08 * (err - bias)
            }
        } else {
            longitudinalG = gpsLongEma.update(gpsG, dtSec)
        }
        sumSinceFix = 0.0
        countSinceFix = 0
    }

    fun lateralG(speedMps: Double?, yawRateCcwRadPerSec: Double?, gpsHeadingRateDegPerSec: Double?): Double? {
        val v = speedMps ?: return null
        val yawCcw = yawRateCcwRadPerSec
            ?: gpsHeadingRateDegPerSec?.let { -Math.toRadians(it) }
            ?: return null
        return Units.mps2ToG(-v * yawCcw)
    }
}
