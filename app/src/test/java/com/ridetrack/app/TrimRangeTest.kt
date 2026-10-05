package com.ridetrack.app

import com.ridetrack.app.share.TrimRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrimRangeTest {
    @Test
    fun `nothing stored is the whole clip`() {
        val t = TrimRange.of(30_000, null, null)
        assertTrue(t.isWhole)
        assertEquals(30_000, t.durationMillis)
    }

    @Test
    fun `handles stay inside the clip and at least a second apart`() {
        val t = TrimRange.whole(30_000).withStart(12_000).withEnd(20_000)
        assertEquals(8_000, t.durationMillis)
        assertFalse(t.isWhole)
        assertEquals(19_000, t.withStart(25_000).startMillis)
        assertEquals(13_000, t.withEnd(5_000).endMillis)
        assertEquals(0, t.withStart(-500).startMillis)
        assertEquals(30_000, t.withEnd(99_000).endMillis)
        assertTrue(t.reset().isWhole)
    }

    @Test
    fun `stored values from a longer clip are clamped`() {
        val t = TrimRange.of(10_000, 8_000, 40_000)
        assertEquals(8_000, t.startMillis)
        assertEquals(10_000, t.endMillis)
    }

    @Test
    fun `overlay clock counts from the trimmed start`() {
        val t = TrimRange.whole(30_000).withStart(12_000)
        assertEquals(1_000_000 + 12_000 + 500, t.wallTime(1_000_000, 500))
    }
}
