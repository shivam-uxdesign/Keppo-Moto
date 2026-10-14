package com.ridetrack.app

import com.ridetrack.app.studio.TimeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeMapTest {
    // 0.1 px per ms; the selected clip is 10–20 s, with 5 s of recording before it and 3 s after.
    private val m = TimeMap(0.1f, atMs = 10_000, endMs = 20_000, leftPx = 500f, rightPx = 300f)

    @Test fun `things after the selected clip make room for the rest of its recording`() {
        assertEquals(500f, m.x(5_000), 0.01f)
        assertEquals(1_500f, m.x(10_000), 0.01f) // the clip itself starts after the unused part
        assertEquals(1_000f, m.xEnd(10_000), 0.01f) // the clip before it doesn't stretch
        assertEquals(2_500f, m.xEnd(20_000), 0.01f)
        assertEquals(2_800f, m.x(20_000), 0.01f)
        assertEquals(3_300f, m.x(25_000), 0.01f)
    }

    @Test fun `positions map back to Reel time, the unused parts to the clip's edges`() {
        assertEquals(5_000L, m.ms(500f))
        assertEquals(10_000L, m.ms(1_200f)) // in the unused part before
        assertTrue(m.unused(1_200f))
        assertEquals(15_000L, m.ms(2_000f))
        assertFalse(m.unused(2_000f))
        assertEquals(20_000L, m.ms(2_600f)) // in the unused part after
        assertEquals(25_000L, m.ms(3_300f))
    }

    @Test fun `nothing selected is a plain scale`() {
        val p = TimeMap(0.2f)
        assertEquals(2_000f, p.x(10_000), 0.01f)
        assertEquals(10_000L, p.ms(2_000f))
    }
}
