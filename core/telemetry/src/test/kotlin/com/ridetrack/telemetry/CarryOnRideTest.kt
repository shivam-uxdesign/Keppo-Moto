package com.ridetrack.telemetry

import com.ridetrack.telemetry.demo.DemoRideModel
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.processing.TelemetryPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A ride carried on after the app was closed: same clock, stats continue, the gap is a break. */
class CarryOnRideTest {
    @Test
    fun `carrying on keeps the stats and the ride's clock, and only counts time from now`() {
        val model = DemoRideModel()
        val sensors = SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = false)
        val before = RideStats(distanceM = 12_000.0, movingMillis = 1_200_000, stoppedMillis = 60_000, breakMillis = 90_000)
        // Ride started 30 min ago (wall), the app is back now: its virtual start is 30 min before "now".
        val startWall = 1_000_000L
        val nowNanos = 30 * 60 * 1_000_000_000L
        val p = TelemetryPipeline(
            DataSourceKind.DEMO, model.calibration, sensors,
            startNanos = 0, startWallMillis = startWall,
            initialStats = before, accountFromNanos = nowNanos,
        )
        var t = 0L
        while (t < 20_000_000_000L) {
            model.step(t).forEach { p.process(it) }
            t += model.stepNanos
        }
        val (frame, _) = p.frame(nowNanos + 20_000_000_000L)
        val s = p.stats
        assertTrue(s.distanceM > before.distanceM, "distance carries on from 12 km")
        assertEquals(before.breakMillis, s.breakMillis)
        // Only the 20 s since carrying on were counted, not the 30 min before.
        val counted = (s.movingMillis + s.stoppedMillis) - (before.movingMillis + before.stoppedMillis)
        assertTrue(counted in 19_000..21_000, "counted $counted ms")
        assertEquals(30 * 60_000L + 20_000L, frame.elapsedMillis)
        assertEquals(startWall + 30 * 60_000L, p.wallMillis(nowNanos))
    }
}
