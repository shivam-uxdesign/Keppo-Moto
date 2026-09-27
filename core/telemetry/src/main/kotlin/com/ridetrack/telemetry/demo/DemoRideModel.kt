package com.ridetrack.telemetry.demo

import com.ridetrack.telemetry.math.Geo
import com.ridetrack.telemetry.math.Units
import com.ridetrack.telemetry.math.Vec3
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.source.AccelReading
import com.ridetrack.telemetry.source.EngineReading
import com.ridetrack.telemetry.source.GyroReading
import com.ridetrack.telemetry.source.LocationReading
import com.ridetrack.telemetry.source.RawReading
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic simulated ride used ONLY by demo mode and tests. It produces physically
 * consistent raw sensor readings (for a phone mounted upright, screen facing the rider)
 * so the real processing pipeline is exercised end to end.
 *
 * The script loops: pull away, cruise, right sweeper, left sweeper, hard brake to a stop,
 * wait, pull away, a sharp right, cruise.
 *
 * With [simulateEngine] it also acts as a simulated OBD link: RPM and gear from a
 * six-speed gearbox, upshifting under acceleration and dropping to neutral at stops.
 */
class DemoRideModel(
    private val startLatitude: Double = 12.9716,
    private val startLongitude: Double = 77.5946,
    private val startHeadingDeg: Double = 20.0,
    seed: Int = 7,
    private val simulateEngine: Boolean = false,
) {
    private data class Segment(val seconds: Double, val accelMps2: Double, val yawRateDegPerSec: Double)

    private val script = listOf(
        Segment(6.0, 0.0, 0.0), // waiting at start
        Segment(8.0, 2.4, 0.0), // pull away to ~69 km/h
        Segment(10.0, 0.0, 0.0), // cruise
        Segment(9.0, -0.4, 12.0), // right sweeper
        Segment(4.0, 0.0, 0.0),
        Segment(9.0, 0.0, -11.0), // left sweeper
        Segment(6.0, 0.3, 0.0),
        Segment(4.0, -5.5, 0.0), // hard brake (~0.56 g)
        Segment(0.0, 0.0, 0.0), // snap to standstill (see below)
        Segment(9.0, 0.0, 0.0), // stopped
        Segment(7.0, 2.0, 0.0), // pull away
        Segment(6.0, 0.0, 20.0), // sharp right
        Segment(12.0, 0.2, 0.0), // cruise
        Segment(8.0, -1.8, 0.0), // ease down
        Segment(4.0, 0.0, 0.0),
    )
    private val loopSeconds = script.sumOf { it.seconds }

    /** Mount used by the simulated phone: portrait, screen facing the rider. */
    val calibration = MountCalibration(up = Vec3.Y, createdAtMillis = 0L)

    private val random = Random(seed)
    private val dt = 0.02 // 50 Hz

    private var t = 0.0
    private var speed = 0.0
    private var heading = startHeadingDeg
    private var lat = startLatitude
    private var lon = startLongitude
    private var altitude = 912.0
    private var leanRad = 0.0
    private var nextGpsAt = 0.0
    private var nextEngineAt = 0.0
    private var gear = 0
    private var stoppedFor = 0.0
    private var rpm = IDLE_RPM

    /** Advances the simulation by one 20 ms step and returns the readings produced. */
    fun step(timeNanos: Long): List<RawReading> {
        val seg = segmentAt(t % loopSeconds)
        speed = (speed + seg.accelMps2 * dt).coerceIn(0.0, 40.0)
        if (seg.accelMps2 < 0 && speed < 0.5) speed = 0.0
        val yawCw = if (speed > 1.0) Math.toRadians(seg.yawRateDegPerSec) else 0.0
        heading = Geo.normalizeDeg(heading + Math.toDegrees(yawCw) * dt)
        val (nLat, nLon) = Geo.destination(lat, lon, heading, speed * dt)
        lat = nLat
        lon = nLon
        altitude += sin(t / 30.0) * speed * dt * 0.03

        val targetLean = atan(speed * yawCw / Units.STANDARD_GRAVITY)
        val leanRate = (targetLean - leanRad) / 0.4
        leanRad += leanRate * dt

        val cal = calibration
        val vertical = cal.up * cos(leanRad) - cal.right * sin(leanRad)
        // Coordinated turn: specific force lies along the bike's own up axis.
        val specific = cal.forward * seg.accelMps2.takeIf { speed > 0.0 || it > 0 }.orZero() +
            cal.up * (Units.STANDARD_GRAVITY / cos(leanRad)) + noise(0.25)
        val gyro = cal.forward * leanRate + vertical * (-yawCw) + noise(0.01)

        val out = ArrayList<RawReading>(3)
        out += AccelReading(timeNanos, specific.x, specific.y, specific.z)
        out += GyroReading(timeNanos, gyro.x, gyro.y, gyro.z)
        if (t >= nextGpsAt) {
            nextGpsAt += 1.0
            out += LocationReading(
                timeNanos = timeNanos,
                latitude = lat + random.nextDouble(-1.0, 1.0) * 1e-6,
                longitude = lon + random.nextDouble(-1.0, 1.0) * 1e-6,
                altitudeM = altitude,
                speedMps = speed,
                bearingDeg = if (speed > 1.0) heading else null,
                horizontalAccuracyM = 4.0,
            )
        }
        if (simulateEngine) {
            updateEngine(seg.accelMps2)
            if (t >= nextEngineAt) {
                nextEngineAt += 0.1 // 10 Hz, typical for a fast OBD PID poll
                out += EngineReading(timeNanos, rpm + random.nextDouble(-40.0, 40.0), gear)
            }
        }
        t += dt
        return out
    }

    private fun updateEngine(accel: Double) {
        val kmh = speed * 3.6
        stoppedFor = if (speed < 0.5) stoppedFor + dt else 0.0
        when {
            stoppedFor > 2.0 -> gear = 0
            gear == 0 && (accel > 0 || speed > 0.5) -> gear = 1
            gear in 1 until RPM_PER_KMH.size -> {
                val shiftAt = if (accel > 1.0) 8_200.0 else 5_200.0
                if (kmh * RPM_PER_KMH[gear - 1] > shiftAt) gear++
            }
        }
        while (gear > 1 && kmh * RPM_PER_KMH[gear - 1] < 2_600.0) gear--
        val target = if (gear == 0) IDLE_RPM else (kmh * RPM_PER_KMH[gear - 1]).coerceAtLeast(if (accel > 0) 2_800.0 else IDLE_RPM)
        rpm += (target - rpm) * (1.0 - kotlin.math.exp(-dt / 0.15))
    }

    val stepNanos: Long = (dt * 1e9).toLong()

    private fun segmentAt(time: Double): Segment {
        var acc = 0.0
        for (s in script) {
            acc += s.seconds
            if (time < acc) return s
        }
        return script.last()
    }

    private fun noise(sigma: Double) = Vec3(
        random.nextDouble(-sigma, sigma),
        random.nextDouble(-sigma, sigma),
        random.nextDouble(-sigma, sigma),
    )

    private fun Double?.orZero() = this ?: 0.0

    companion object {
        /** Engine RPM per km/h in gears 1..6 (roughly a 400 cc single). */
        private val RPM_PER_KMH = doubleArrayOf(260.0, 180.0, 140.0, 117.0, 101.0, 90.0)
        private const val IDLE_RPM = 1_350.0
        /** Redline used by demo mode's rev meter. */
        const val DEMO_REDLINE_RPM = 9_500
    }
}
