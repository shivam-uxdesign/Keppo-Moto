package com.ridetrack.app

import com.ridetrack.app.moments.MomentTopSpeed
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MomentTopSpeedTest {
    private fun s(t: Long, v: Double?) = TelemetrySample(
        timeMillis = t, latitude = null, longitude = null, speedMps = v, altitudeM = null, headingDeg = null,
        longitudinalG = null, lateralG = null, leanDeg = null, gpsAccuracyM = null,
    )

    @Test
    fun `the fastest inside the clip, not around it`() {
        val samples = listOf(s(0, 30.0), s(1_000, 12.0), s(2_000, 18.5), s(3_000, null), s(4_000, 14.0), s(5_000, 40.0))
        assertEquals(18.5, MomentTopSpeed.of(samples, 1_000, 4_000))
    }

    @Test
    fun `no speeds in the clip, no top speed`() {
        assertNull(MomentTopSpeed.of(listOf(s(0, null), s(1_000, null)), 0, 1_000))
        assertNull(MomentTopSpeed.of(listOf(s(0, 10.0)), 5_000, 9_000))
    }
}
