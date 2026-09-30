package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.MomentTriggers
import kotlin.test.Test
import kotlin.test.assertEquals

class MomentTriggersTest {
    @Test
    fun `rider thresholds map onto the detector with release below trigger`() {
        val t = MomentTriggers(brakeG = 0.6, accelG = 0.4, leanDeg = 32.0).thresholds()
        assertEquals(0.6, t.hardBrakeG, 1e-9)
        assertEquals(0.3, t.hardBrakeReleaseG, 1e-9)
        assertEquals(0.4, t.strongAccelG, 1e-9)
        assertEquals(0.16, t.strongAccelReleaseG, 1e-9)
        assertEquals(32.0, t.significantLeanDeg, 1e-9)
        assertEquals(24.0, t.significantLeanReleaseDeg, 1e-9)
    }

    @Test
    fun `defaults match the previous fixed moment thresholds`() {
        val t = MomentTriggers().thresholds()
        assertEquals(0.5, t.hardBrakeG, 1e-9)
        assertEquals(0.3, t.strongAccelG, 1e-9)
        assertEquals(20.0, t.significantLeanDeg, 1e-9)
        assertEquals(15.0, t.significantLeanReleaseDeg, 1e-9)
    }
}
