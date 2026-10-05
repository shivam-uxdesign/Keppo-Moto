package com.ridetrack.telemetry

import com.ridetrack.telemetry.demo.DemoRideModel
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.processing.BreakDetector
import com.ridetrack.telemetry.processing.BreakDetector.Transition
import com.ridetrack.telemetry.processing.TelemetryPipeline
import com.ridetrack.telemetry.source.LocationReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreakDetectorTest {
    private val lat = 28.5965
    private val lon = 77.0810
    /** ~111 m of latitude. */
    private val far = 0.001

    private fun input(
        now: Long,
        stoppedSince: Long? = 0,
        tilt: Double? = 5.0,
        engine: Boolean? = null,
        speed: Double? = 0.0,
        accuracy: Double? = 3.0,
        la: Double? = lat,
        crash: Boolean = false,
    ) = BreakDetector.Input(now, stoppedSince, tilt, engine, speed, accuracy, la, lon, crash)

    /** Feeds one input per second from [from] to [to] ms; returns each transition with its time. */
    private fun BreakDetector.run(from: Long, to: Long, f: (Long) -> BreakDetector.Input): List<Pair<Long, Transition>> {
        val out = ArrayList<Pair<Long, Transition>>()
        var t = from
        while (t <= to) {
            update(f(t))?.let { out += t to it }
            t += 1_000
        }
        return out
    }

    @Test
    fun `a 4 minute traffic stop is not a break, 5 minutes is, back-dated to the stop`() {
        val d = BreakDetector()
        assertTrue(d.run(0, 4 * 60_000) { input(it) }.isEmpty())
        val t = d.run(4 * 60_000 + 1_000, 6 * 60_000) { input(it) }
        assertEquals(listOf(5 * 60_000L to Transition.STARTED), t)
        assertEquals(0L, d.breakStartMillis)
        assertTrue(d.reason!!.startsWith("stopped"))
    }

    @Test
    fun `phone taken off the mount starts a break after 20 s`() {
        // Today's Dwarka stop: lean pinned at -70° from 40 s into the stop.
        val d = BreakDetector()
        val t = d.run(0, 120_000) { input(it, tilt = if (it >= 40_000) 70.0 else 5.0) }
        assertEquals(listOf(60_000L to Transition.STARTED), t)
        assertEquals(0L, d.breakStartMillis)
        assertEquals("phone off the mount", d.reason)
    }

    @Test
    fun `side stand tilt is not off the mount`() {
        val d = BreakDetector(afterMillis = 0)
        assertTrue(d.run(0, 10 * 60_000) { input(it, tilt = 15.0) }.isEmpty())
    }

    @Test
    fun `engine off for a minute starts a break, rpm or OBD going quiet`() {
        val a = BreakDetector()
        assertEquals(listOf(70_000L to Transition.STARTED), a.run(0, 120_000) { input(it, engine = it < 10_000) })
        val b = BreakDetector()
        // OBD answered while idling, then went silent with the ignition.
        assertEquals(listOf(70_000L to Transition.STARTED), b.run(0, 120_000) { input(it, engine = if (it < 10_000) true else null) })
        val c = BreakDetector()
        // Never any engine data: only the timer.
        assertTrue(c.run(0, 120_000) { input(it, engine = null) }.isEmpty())
    }

    @Test
    fun `walking and GPS spikes don't end a break, riding off does`() {
        val d = BreakDetector()
        d.run(0, 5 * 60_000) { input(it) }
        assertTrue(d.onBreak)
        // Walking about at 1.3 m/s.
        assertTrue(d.run(300_000, 400_000) { input(it, speed = 1.3) }.isEmpty())
        // GPS back with a fake 2 m/s for 2 s (happened at 11:30:42).
        assertTrue(d.run(400_000, 402_000) { input(it, speed = 2.0) }.isEmpty())
        // Riding, but still inside the car park (< 100 m).
        // (Riding clears the stop in the pipeline, so stoppedSince is null from here.)
        assertTrue(d.run(410_000, 420_000) { input(it, stoppedSince = null, speed = 6.0) }.isEmpty())
        // Riding away.
        val t = d.run(430_000, 440_000) { input(it, stoppedSince = null, speed = 8.0, la = lat + far) }
        assertEquals(Transition.ENDED, t.single().second)
        assertFalse(d.onBreak)
        assertNull(d.breakStartMillis)
    }

    @Test
    fun `fast readings on a poor fix don't end a break`() {
        val d = BreakDetector()
        d.run(0, 5 * 60_000) { input(it) }
        assertTrue(d.run(300_000, 320_000) { input(it, speed = 9.0, accuracy = 45.0, la = lat + far) }.isEmpty())
    }

    @Test
    fun `a suspected crash is never shown as a break`() {
        val d = BreakDetector()
        assertTrue(d.run(0, 10 * 60_000) { input(it, tilt = 85.0, crash = true) }.isEmpty())
    }

    @Test
    fun `no stop, no break`() {
        val d = BreakDetector()
        assertTrue(d.run(0, 10 * 60_000) { input(it, stoppedSince = null, tilt = 80.0) }.isEmpty())
    }

    @Test
    fun `pipeline moves the stop into break time and records the event`() {
        val model = DemoRideModel()
        val p = TelemetryPipeline(
            DataSourceKind.DEMO, model.calibration, SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = false),
            startNanos = 0, startWallMillis = 1_000_000,
        )
        p.start()
        val events = ArrayList<com.ridetrack.telemetry.model.RideEvent>()
        val sec = 1_000_000_000L
        // Ride 60 s at 10 m/s, then stand still for 7 minutes, then ride away.
        var t = 0L
        var la = lat
        fun gps(speed: Double) {
            la += speed / 111_000.0
            events += p.process(LocationReading(t, la, lon, null, speed, 0.0, 3.0))
            events += p.frame(t).second
        }
        while (t < 60 * sec) { gps(10.0); t += sec }
        val stopAt = t
        while (t < stopAt + 7 * 60 * sec) { gps(0.0); t += sec }
        assertTrue(p.onBreak)
        val s = p.stats
        assertTrue(s.breakMillis >= 6 * 60_000, "break holds the stop (${s.breakMillis})")
        assertTrue(s.stoppedMillis < 30_000, "stop time moved to the break (${s.stoppedMillis})")
        while (t < stopAt + 8 * 60 * sec) { gps(10.0); t += sec }
        assertFalse(p.onBreak)
        val types = events.map { it.type }
        assertEquals(1, types.count { it == RideEventType.BREAK_START })
        assertEquals(1, types.count { it == RideEventType.BREAK_END })
        val start = events.first { it.type == RideEventType.BREAK_START }
        assertTrue(start.timeMillis < 1_000_000 + stopAt / 1_000_000 + 10_000, "back-dated to the stop")
    }
}
