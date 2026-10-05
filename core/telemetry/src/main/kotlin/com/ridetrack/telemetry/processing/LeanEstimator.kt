package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.math.Ema
import com.ridetrack.telemetry.math.Units
import com.ridetrack.telemetry.math.Vec3
import com.ridetrack.telemetry.model.LeanConfidence
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.source.AccelReading
import com.ridetrack.telemetry.source.GyroReading
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Complementary-filter lean-angle estimator. Positive lean = leaning right.
 *
 * The gyroscope roll rate about the bike's forward axis is integrated for responsiveness.
 * Drift is removed by pulling the estimate toward a reference:
 *  - when moving with known speed: the coordinated-turn lean `atan(v·ω_yaw / g)`. (In a
 *    steady turn the accelerometer reads ~0° lean because centripetal and gravity forces
 *    combine along the bike's own axis, so it cannot be used as the reference there.)
 *  - when slow/stationary: the gravity direction from the accelerometer.
 *  - when speed is unknown: the gravity direction with a long time constant, flagged LOW.
 */
class LeanEstimator(
    calibration: MountCalibration?,
    private val sensors: SensorAvailability,
) {
    /** Can change mid-ride (auto-calibration / "Calibrate now"); the estimate restarts. */
    var calibration: MountCalibration? = calibration
        set(value) {
            field = value
            leanRad = null
            lastGyroNanos = null
        }

    private val enabled get() = calibration != null && sensors.canEstimateLean

    private var leanRad: Double? = null
    private var lastGyroNanos: Long? = null
    private var lastAccelNanos: Long? = null
    private val yawRateEma = Ema(0.25)
    private val accX = Ema(0.4)
    private val accY = Ema(0.4)
    private val accZ = Ema(0.4)

    var speedMps: Double? = null
    var sensorUnreliable: Boolean = false

    /** World-frame yaw rate in rad/s, counter-clockwise (left turn) positive. */
    val yawRateRadPerSec: Double? get() = if (enabled) yawRateEma.value else null

    val leanDeg: Double? get() = if (enabled) leanRad?.let { Math.toDegrees(it) } else null

    /**
     * How far (degrees) the phone is tilted from its mounted position, from smoothed gravity.
     * Large and lasting while stopped = the phone is off the mount (in a pocket or a hand).
     */
    val mountTiltDeg: Double?
        get() {
            val cal = calibration ?: return null
            val a = Vec3(accX.value ?: return null, accY.value ?: return null, accZ.value ?: return null)
            if (a.norm < 1e-3) return null
            val c = ((a dot cal.up) / (a.norm * cal.up.norm)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(c))
        }

    val confidence: LeanConfidence
        get() = when {
            !enabled || leanRad == null -> LeanConfidence.UNAVAILABLE
            sensorUnreliable || speedMps == null -> LeanConfidence.LOW
            else -> LeanConfidence.GOOD
        }

    fun onAccel(r: AccelReading) {
        if (!enabled) return
        val dt = lastAccelNanos?.let { (r.timeNanos - it) / 1e9 } ?: 0.0
        lastAccelNanos = r.timeNanos
        accX.update(r.x, dt)
        accY.update(r.y, dt)
        accZ.update(r.z, dt)
        if (leanRad == null) leanRad = gravityLean() ?: 0.0
    }

    fun onGyro(r: GyroReading) {
        val cal = calibration ?: return
        if (!enabled) return
        val last = lastGyroNanos
        lastGyroNanos = r.timeNanos
        if (last == null) return
        val dt = (r.timeNanos - last) / 1e9
        if (dt <= 0.0 || dt > 0.2) return // gap: skip integration, reference will re-converge

        val w = Vec3(r.x, r.y, r.z)
        var lean = leanRad ?: gravityLean() ?: 0.0
        lean += (w dot cal.forward) * dt

        val vertical = cal.up * cos(lean) - cal.right * sin(lean)
        val yawRate = yawRateEma.update(w dot vertical, dt)

        val speed = speedMps
        val (reference, tau) = when {
            speed != null && speed >= 4.0 -> dynamicLean(speed, yawRate) to 1.0
            speed != null -> (gravityLean() ?: lean) to 0.5
            else -> (gravityLean() ?: lean) to 4.0
        }
        lean += (reference - lean) * (1.0 - exp(-dt / tau))
        leanRad = lean.coerceIn(-MAX_LEAN_RAD, MAX_LEAN_RAD)
    }

    private fun dynamicLean(speed: Double, yawRateCcw: Double): Double =
        atan(-speed * yawRateCcw / Units.STANDARD_GRAVITY).coerceIn(-MAX_LEAN_RAD, MAX_LEAN_RAD)

    private fun gravityLean(): Double? {
        val cal = calibration ?: return null
        val x = accX.value ?: return null
        val a = Vec3(x, accY.value ?: return null, accZ.value ?: return null)
        // Only trust gravity when the accelerometer isn't dominated by other forces.
        if (abs(a.norm - Units.STANDARD_GRAVITY) > 0.15 * Units.STANDARD_GRAVITY) return null
        return atan2(-(a dot cal.right), a dot cal.up)
    }

    companion object {
        private val MAX_LEAN_RAD = Math.toRadians(70.0)
    }
}
