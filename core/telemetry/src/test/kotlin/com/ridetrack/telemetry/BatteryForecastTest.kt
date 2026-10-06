package com.ridetrack.telemetry

import com.ridetrack.telemetry.processing.BatteryForecast
import com.ridetrack.telemetry.processing.BatteryWarnings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryForecastTest {
    private val min = 60_000L

    @Test
    fun `24 percent an hour at 40 percent is about 100 minutes`() {
        val f = BatteryForecast()
        // 1 % every 2.5 min.
        for (i in 0..8) f.add(i * 150_000L, 60 - i, charging = false)
        assertEquals(24.0, f.drainPerHour()!!, 0.5)
        assertEquals(130.0, f.minutesLeft(52, null)!!.toDouble(), 3.0)
    }

    @Test
    fun `too early, past rides stand in`() {
        val f = BatteryForecast()
        f.add(0, 50, false)
        f.add(min, 50, false)
        assertNull(f.drainPerHour())
        assertEquals(150, f.minutesLeft(50, fallbackPerHour = 20.0))
        assertNull(BatteryForecast().minutesLeft(50, null))
    }

    @Test
    fun `plugging in starts again, and a charger that keeps up gives no estimate`() {
        val f = BatteryForecast()
        for (i in 0..6) f.add(i * min, 50 - i, false)
        for (i in 7..15) f.add(i * min, 44 + (i - 7) / 3, true)
        assertTrue(f.drainPerHour()!! < 0)
        assertNull(f.minutesLeft(47, null))
    }

    @Test
    fun `warnings fire once each, at 30 and 15 percent, under 20 minutes, and for a weak charger`() {
        val w = BatteryWarnings()
        assertNull(w.check(45, false, 120))
        assertNotNull(w.check(30, false, 75)).also { assertTrue("about 1 h 15 min" in it) }
        assertNull(w.check(29, false, 72))
        assertNotNull(w.check(15, false, 37))
        assertNull(w.check(14, false, 35))
        assertNotNull(w.check(8, false, 19))
        assertNotNull(BatteryWarnings().check(60, true, 100)).also { assertTrue("Charging" in it) }
        assertNull(BatteryWarnings().check(60, true, null))
    }
}
