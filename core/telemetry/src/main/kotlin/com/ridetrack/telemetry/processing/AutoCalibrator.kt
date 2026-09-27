package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.math.Units
import com.ridetrack.telemetry.math.Vec3
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.source.AccelReading
import com.ridetrack.telemetry.source.GyroReading
import kotlin.math.abs
import kotlin.math.acos

/**
 * Learns the phone mount while riding. When the bike goes straight at a steady speed it
 * is upright and not accelerating, so the mean accelerometer vector over a few seconds is
 * gravity in the phone frame — with the rider aboard and the phone in its riding position,
 * which a parked calibration can't guarantee.
 *
 * The caller reports whether conditions are steady via [steady] (from GPS); readings
 * arriving while not steady discard the current window. Accepted windows are averaged,
 * so road gradient and vibration even out over the ride.
 */
class AutoCalibrator(
    private val windowNanos: Long = 4_000_000_000L,
    private val maxWindows: Int = 8,
    private val maxMeanGyroRadPerSec: Double = 0.25,
) {
    /** Set by the pipeline on each GPS fix: straight, steady speed, good fix. */
    var steady: Boolean = false
        set(value) {
            if (!value) resetWindow()
            field = value
        }

    private var windowStart: Long? = null
    private var sum = Vec3.ZERO
    private var n = 0
    private var gyroSum = 0.0
    private var gyroN = 0

    private var acceptedSum = Vec3.ZERO
    var acceptedWindows = 0
        private set

    val isDone: Boolean get() = acceptedWindows >= maxWindows

    /** Returns a new calibration when a window completes, else null. */
    fun onAccel(r: AccelReading, nowMillis: Long): MountCalibration? {
        if (!steady || isDone) return null
        val start = windowStart ?: r.timeNanos.also { windowStart = it }
        sum += Vec3(r.x, r.y, r.z)
        n++
        if (r.timeNanos - start < windowNanos) return null

        val count = n
        val mean = sum / count.toDouble()
        val gyroOk = gyroN == 0 || gyroSum / gyroN <= maxMeanGyroRadPerSec
        resetWindow()
        if (count < 50 || !gyroOk) return null
        if (abs(mean.norm - Units.STANDARD_GRAVITY) > 0.8) return null
        acceptedSum += mean.normalized()
        acceptedWindows++
        return MountCalibration(up = acceptedSum.normalized(), createdAtMillis = nowMillis)
    }

    fun onGyro(r: GyroReading) {
        if (!steady || windowStart == null) return
        gyroSum += Vec3(r.x, r.y, r.z).norm
        gyroN++
    }

    private fun resetWindow() {
        windowStart = null
        sum = Vec3.ZERO
        n = 0
        gyroSum = 0.0
        gyroN = 0
    }

    companion object {
        /** Angle between two mounts' up vectors, degrees. */
        fun differenceDeg(a: MountCalibration, b: MountCalibration): Double =
            Math.toDegrees(acos((a.up.normalized() dot b.up.normalized()).coerceIn(-1.0, 1.0)))
    }
}
