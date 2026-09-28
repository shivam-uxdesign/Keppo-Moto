package com.ridetrack.telemetry

import com.ridetrack.telemetry.demo.DemoRideModel
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.moments.MomentPlanner
import com.ridetrack.telemetry.moments.MomentTriggers
import com.ridetrack.telemetry.moments.MomentWindow
import com.ridetrack.telemetry.moments.PhotoScheduler
import com.ridetrack.telemetry.processing.TelemetryPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentsTest {
    private fun ev(type: RideEventType, t: Long, v: Double = 0.6) = RideEvent(type, t, 1.0, 2.0, 10.0, v)

    @Test
    fun `window is 10 s either side of the event start`() {
        val p = MomentPlanner()
        p.add(ev(RideEventType.HARD_BRAKE, 100_000), episodeEndMillis = 101_500)
        assertTrue(p.due(109_999).isEmpty())
        val w = p.due(110_000).single()
        assertEquals(90_000, w.startMillis)
        assertEquals(110_000, w.endMillis)
        assertEquals(100_000, w.anchorMillis)
    }

    @Test
    fun `long episodes stretch the clip, capped at 40 s`() {
        val p = MomentPlanner()
        p.add(ev(RideEventType.SIGNIFICANT_LEAN, 100_000, 24.0), episodeEndMillis = 115_000)
        assertEquals(117_000, p.flush(200_000).single().endMillis)
        val q = MomentPlanner()
        q.add(ev(RideEventType.SIGNIFICANT_LEAN, 100_000, 24.0), episodeEndMillis = 190_000)
        val w = q.flush(300_000).single()
        assertEquals(40_000, w.endMillis - w.startMillis)
    }

    @Test
    fun `overlapping events merge into one clip with both types`() {
        val p = MomentPlanner()
        p.add(ev(RideEventType.HARD_BRAKE, 100_000, -0.6), 101_000)
        p.add(ev(RideEventType.SIGNIFICANT_LEAN, 104_000, -31.0), 106_000)
        val w = p.due(200_000).single()
        assertEquals(setOf(RideEventType.HARD_BRAKE, RideEventType.SIGNIFICANT_LEAN), w.types)
        assertEquals(90_000, w.startMillis)
        assertEquals(114_000, w.endMillis)
        assertEquals(-31.0, w.peakValue)
    }

    @Test
    fun `separate events make separate clips without re-filming footage`() {
        val p = MomentPlanner()
        p.add(ev(RideEventType.HARD_BRAKE, 100_000), 101_000)
        val first = p.due(110_000).single()
        // Starts 8 s after the first clip ended: its lead-in is trimmed to avoid overlap.
        p.add(ev(RideEventType.STRONG_ACCELERATION, 118_000), 119_000)
        val second = p.due(128_000).single()
        assertEquals(first.endMillis, second.startMillis)
        // An event inside an already-saved clip is not filmed twice.
        p.add(ev(RideEventType.HARD_BRAKE, 125_000), 126_000)
        assertTrue(p.flush(200_000).isEmpty())
    }

    @Test
    fun `photos every interval of moving time and once per long stop`() {
        val s = PhotoScheduler(intervalMillis = 600_000)
        assertFalse(s.onTick(0, 599_000, isStopped = false))
        assertTrue(s.onTick(1, 600_000, isStopped = false))
        assertFalse(s.onTick(2, 700_000, isStopped = false))
        assertFalse(s.onTick(10_000, 700_000, isStopped = true))
        assertTrue(s.onTick(70_000, 700_000, isStopped = true))
        assertFalse(s.onTick(200_000, 700_000, isStopped = true))
        assertTrue(s.onTick(300_000, 1_200_000, isStopped = false))
    }

    @Test
    fun `demo ride triggers moments at 20 deg lean and hard braking`() {
        val model = DemoRideModel()
        val pipeline = TelemetryPipeline(
            DataSourceKind.DEMO, model.calibration,
            SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = false),
            startNanos = 0, startWallMillis = 0, momentTriggers = MomentTriggers(),
        )
        val planner = MomentPlanner()
        val windows = ArrayList<MomentWindow>()
        var t = 0L
        var step = 0
        while (t < 90_000_000_000L) {
            model.step(t).forEach { pipeline.process(it) }
            if (step % 10 == 0) {
                pipeline.frame(t)
                pipeline.takeMomentEvents().forEach { (e, at) -> planner.add(e, at) }
                windows += planner.due(t / 1_000_000)
            }
            t += model.stepNanos
            step++
        }
        windows += planner.flush(t / 1_000_000)
        val types = windows.flatMap { it.types }.toSet()
        assertTrue(RideEventType.HARD_BRAKE in types, "types $types")
        assertTrue(RideEventType.SIGNIFICANT_LEAN in types, "types $types")
        assertTrue(windows.all { it.endMillis - it.startMillis in 1..40_000 })
        // Clips never overlap.
        windows.zipWithNext().forEach { (a, b) -> assertTrue(b.startMillis >= a.endMillis) }

        val off = TelemetryPipeline(DataSourceKind.DEMO, model.calibration, SensorAvailability(true, true, false), 0, 0)
        assertTrue(off.takeMomentEvents().isEmpty())
    }
}
