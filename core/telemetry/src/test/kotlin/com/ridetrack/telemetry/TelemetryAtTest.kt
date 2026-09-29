package com.ridetrack.telemetry

import com.ridetrack.telemetry.model.TelemetrySample
import com.ridetrack.telemetry.moments.telemetryAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelemetryAtTest {
    private fun s(t: Long, speed: Double?, lean: Double?) =
        TelemetrySample(t, 12.0 + t / 1e6, 77.0, speed, null, null, 0.1, null, lean, 4.0)

    private val track = listOf(s(1_000, 10.0, 0.0), s(2_000, 20.0, 30.0), s(3_000, 30.0, null), s(4_000, 40.0, null))

    @Test
    fun `interpolates between samples`() {
        val p = telemetryAt(track, 1_500)!!
        assertEquals(15.0, p.speedMps!!, 1e-9)
        assertEquals(15.0, p.leanDeg!!, 1e-9)
        assertEquals(12.0015, p.latitude!!, 1e-9)
    }

    @Test
    fun `unknown stays unknown beyond the nearest sample`() {
        // Lean known at 2 s, unknown after: within 1.5 s we hold the last value, then it's gone.
        assertEquals(30.0, telemetryAt(track, 2_400)!!.leanDeg!!, 1e-9)
        assertNull(telemetryAt(track, 3_600)!!.leanDeg)
        assertEquals(35.0, telemetryAt(track, 3_500)!!.speedMps!!, 1e-9)
    }

    @Test
    fun `nothing outside the ride`() {
        assertNull(telemetryAt(track, -5_000))
        assertNull(telemetryAt(track, 60_000))
        assertNull(telemetryAt(emptyList(), 0))
    }
}
