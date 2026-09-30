package com.ridetrack.telemetry

import com.ridetrack.telemetry.demo.DemoRideModel
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.processing.TelemetryPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualPauseTest {
    /** Rides the demo route, pausing between [pauseFrom] and [pauseTo] seconds; stats at each point. */
    private fun ride(pauseFrom: Double, pauseTo: Double, until: Double): Triple<RideStats, RideStats, RideStats> {
        val model = DemoRideModel()
        val pipeline = TelemetryPipeline(
            DataSourceKind.DEMO, model.calibration, SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = false),
            startNanos = 0, startWallMillis = 1_000_000,
        )
        pipeline.start()
        var atPause: RideStats? = null
        var atResume: RideStats? = null
        var t = 0L
        while (t < until * 1e9) {
            val sec = t / 1e9
            if (atPause == null && sec >= pauseFrom) { pipeline.frame(t); atPause = pipeline.stats; pipeline.manuallyPaused = true }
            if (atResume == null && sec >= pauseTo) { pipeline.frame(t); atResume = pipeline.stats; pipeline.manuallyPaused = false }
            model.step(t).forEach { pipeline.process(it) }
            t += model.stepNanos
        }
        pipeline.frame(t)
        return Triple(atPause!!, atResume!!, pipeline.stats)
    }

    @Test
    fun `a manual pause freezes distance and moving time, and they resume after`() {
        val (atPause, atResume, end) = ride(pauseFrom = 20.0, pauseTo = 40.0, until = 60.0)
        assertEquals(atPause.distanceM, atResume.distanceM, 1e-6)
        assertEquals(atPause.movingMillis.toDouble(), atResume.movingMillis.toDouble(), 1_100.0)
        assertTrue(atResume.stoppedMillis - atPause.stoppedMillis >= 19_000, "paused time counts as stopped")
        assertTrue(end.distanceM > atResume.distanceM + 50, "distance counts again after resuming")
        assertTrue(end.movingMillis > atResume.movingMillis + 15_000)
    }
}
