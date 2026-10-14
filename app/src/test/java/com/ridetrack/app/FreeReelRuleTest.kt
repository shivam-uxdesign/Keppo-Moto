package com.ridetrack.app

import com.ridetrack.app.pro.FreeReelRule
import com.ridetrack.app.pro.FreeReelRule.WEEK_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeReelRuleTest {
    private val day = 86_400_000L
    private val t0 = 1_760_000_000_000L

    @Test
    fun `one free Reel a week, back 7 days after it's made`() {
        assertTrue(FreeReelRule.available(null, t0))
        assertNull(FreeReelRule.nextAt(null, t0))
        assertFalse(FreeReelRule.available(t0, t0 + 3 * day))
        assertEquals(t0 + WEEK_MS, FreeReelRule.nextAt(t0, t0 + 3 * day))
        assertTrue(FreeReelRule.available(t0, t0 + WEEK_MS))
        // A clock set back doesn't lock the rider out for longer.
        assertTrue(FreeReelRule.available(t0, t0 - day))
    }

    @Test
    fun `the wait reads in days, tomorrow or hours`() {
        assertEquals("in 4 days", FreeReelRule.wait(t0 + 4 * day, t0))
        assertEquals("tomorrow", FreeReelRule.wait(t0 + 20 * 3_600_000L + 1, t0 - 10 * 3_600_000L))
        assertEquals("in 5 hours", FreeReelRule.wait(t0 + 5 * 3_600_000L, t0))
        assertEquals("within the hour", FreeReelRule.wait(t0 + 30 * 60_000L, t0))
    }
}
