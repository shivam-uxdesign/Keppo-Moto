package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.SoundCoverage
import com.ridetrack.telemetry.moments.SoundShortfall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundChecksTest {
    @Test
    fun `sound that stops early and has a hole is described`() {
        val c = SoundCoverage()
        var t = 0L
        while (t <= 45_000_000L) { c.onVideo(t); t += 33_333 }
        t = 0L
        while (t <= 41_000_000L) {
            if (t !in 20_000_000L..25_000_000L) c.onSound(t)
            t += 23_220
        }
        assertEquals(1, c.holes)
        assertEquals("sound 0.0–41.0 s of 45.0 s, 1 hole (5.0 s)", c.summary())
    }

    @Test
    fun `a video without sound says so`() {
        assertEquals("no sound", SoundCoverage().summary())
    }

    @Test
    fun `a mic that keeps up reports nothing, one that falls behind reports what it lost`() {
        val s = SoundShortfall(44_100)
        assertNull(s.onRead(0, 1024))
        var now = 0L
        var lost: Long? = null
        // 10 s of reads that each give 1024 frames: on time.
        repeat(431) { now += 23_220; lost = s.onRead(now, 1024) ?: lost }
        assertNull(lost)
        // Then 10 s where half a second of reads came back empty (dropped).
        repeat(431) { i -> now += 23_220; s.onRead(now, if (i < 22) 0 else 1024)?.let { lost = it } }
        assertEquals(true, lost!! in 450_000L..560_000L, "lost $lost")
    }
}
