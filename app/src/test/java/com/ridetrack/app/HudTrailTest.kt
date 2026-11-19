package com.ridetrack.app

import com.ridetrack.app.hud.HudTrail
import com.ridetrack.app.hud.HudTrail.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudTrailTest {
    private class Mem : HudTrail.Store {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(values: Map<String, String?>) = values.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
    }

    @Test fun `a ride where the pop-up never went away says nothing`() {
        val t = HudTrail(Mem())
        t.riding("r1"); t.shown("r1")
        assertNull(t.ended())
    }

    @Test fun `each reason is counted, and only the unexpected ones are a problem`() {
        val t = HudTrail(Mem())
        t.shown("r1"); t.hid("r1", Why.APP_OPEN); t.shown("r1"); t.hid("r1", Why.APP_OPEN)
        assertFalse(t.unexpected)
        t.hid("r1", Why.NOT_SHOWN)
        assertTrue(t.unexpected)
        assertEquals("The pop-up went away 3 times this ride: 2× Keppo Moto was opened; 1× Android refused to show it", t.ended())
        assertNull(t.ended())
    }

    @Test fun `the app restarting mid-ride with the pop-up up counts as a restart, once`() {
        val disk = Mem()
        HudTrail(disk).apply { riding("r1"); shown("r1") }
        // The process dies here; a new one carries the same ride on.
        val after = HudTrail(disk)
        after.riding("r1"); after.riding("r1")
        assertTrue(after.unexpected)
        assertEquals("The pop-up went away 1 time this ride: 1× the app was closed by Android or crashed, and restarted", after.ended())
    }

    @Test fun `a restart while the pop-up was already down, or into a new ride, is not counted`() {
        val disk = Mem()
        HudTrail(disk).apply { shown("r1"); hid("r1", Why.APP_OPEN) }
        HudTrail(disk).apply { riding("r1"); assertFalse(unexpected) }
        val d2 = Mem()
        HudTrail(d2).apply { shown("r1") }
        HudTrail(d2).apply { riding("r2"); assertNull(ended()) }
    }
}

class KeepRunningTest {
    @Test fun `a phone that may close the app mid-ride is a pre-ride problem with a fix`() {
        val c = com.ridetrack.app.ui.home.Readiness.checks(com.ridetrack.app.ui.home.ReadyInputs(keepRunning = false))
        val bg = c.first { it.label == "Background" }
        assertEquals(com.ridetrack.app.ui.home.CheckState.PROBLEM, bg.state)
        assertEquals(com.ridetrack.app.ui.home.ReadyFix.KEEP_RUNNING, bg.fix)
        assertTrue(com.ridetrack.app.ui.home.Readiness.checks(com.ridetrack.app.ui.home.ReadyInputs()).none { it.label == "Background" })
    }

    @Test fun `an exit is mid-ride when the ride's last point is close to it`() {
        val last = 1_000_000_000L
        assertTrue(com.ridetrack.app.ride.RideContinuation.midRide(last + 5_000, last))
        assertTrue(com.ridetrack.app.ride.RideContinuation.midRide(last - 30_000, last)) // carried on already
        assertFalse(com.ridetrack.app.ride.RideContinuation.midRide(last + 10 * 60_000, last))
        assertFalse(com.ridetrack.app.ride.RideContinuation.midRide(last, null))
    }
}
