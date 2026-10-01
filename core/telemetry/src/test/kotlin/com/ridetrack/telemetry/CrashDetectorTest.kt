package com.ridetrack.telemetry

import com.ridetrack.telemetry.processing.CrashDetector
import com.ridetrack.telemetry.processing.CrashSuspected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CrashDetectorTest {
    private val ms = 1_000_000L

    /** Feeds 50 Hz samples from [from] to [to] ms; returns the first report. */
    private fun CrashDetector.feed(from: Long, to: Long, g: (Long) -> Double, speed: (Long) -> Double?, lean: (Long) -> Double?): CrashSuspected? {
        var out: CrashSuspected? = null
        var t = from
        while (t <= to) {
            val r = onAccel(t * ms, g(t), speed(t), lean(t))
            if (out == null) out = r
            t += 20
        }
        return out
    }

    @Test
    fun `impact at speed, then the bike on its side, is a crash`() {
        val d = CrashDetector()
        assertNull(d.feed(0, 10_000, { 1.0 }, { 15.0 }, { 10.0 }))
        val r = d.feed(10_020, 25_000, { t -> if (t in 10_020..10_100) 6.5 else 1.0 }, { t -> if (t < 10_500) 12.0 else 0.0 }, { t -> if (t > 10_500) 85.0 else 10.0 })
        assertNotNull(r)
        assertEquals(12.0, r.speedBeforeMps)
        assertEquals(6.5, r.peakG)
    }

    @Test
    fun `impact then lying still upright is a crash too`() {
        val d = CrashDetector()
        d.feed(0, 5_000, { 1.0 }, { 20.0 }, { 0.0 })
        val r = d.feed(5_020, 20_000, { t -> if (t < 5_100) 5.0 else 1.02 }, { t -> if (t < 5_400) 10.0 else null }, { null })
        assertNotNull(r)
    }

    @Test
    fun `a pothole hit followed by more riding is not`() {
        val d = CrashDetector()
        d.feed(0, 5_000, { 1.0 }, { 18.0 }, { 0.0 })
        assertNull(d.feed(5_020, 30_000, { t -> if (t < 5_060) 5.5 else 1.0 + 0.3 * ((t / 20) % 2) }, { 17.0 }, { 5.0 }))
    }

    @Test
    fun `a phone dropped at a standstill is not`() {
        val d = CrashDetector()
        assertNull(d.feed(0, 20_000, { t -> if (t in 8_000..8_040) 9.0 else 1.0 }, { 0.0 }, { 0.0 }))
    }

    @Test
    fun `hard braking to a stop is not`() {
        val d = CrashDetector()
        d.feed(0, 5_000, { 1.0 }, { 20.0 }, { 0.0 })
        // 1.2 g of braking stays far below an impact.
        assertNull(d.feed(5_020, 20_000, { t -> if (t < 8_000) 1.6 else 1.0 }, { t -> if (t < 8_000) 20.0 - (t - 5_000) / 150.0 else 0.0 }, { 0.0 }))
    }

    @Test
    fun `high sensitivity counts a lighter impact`() {
        val d = CrashDetector(impactG = 3.0)
        d.feed(0, 5_000, { 1.0 }, { 15.0 }, { 0.0 })
        assertNotNull(d.feed(5_020, 20_000, { t -> if (t < 5_100) 3.4 else 1.0 }, { t -> if (t < 5_300) 10.0 else 0.0 }, { t -> if (t > 5_300) 70.0 else 0.0 }))
    }

    @Test
    fun `reports once, not again while the bike lies there`() {
        val d = CrashDetector()
        d.feed(0, 5_000, { 1.0 }, { 15.0 }, { 0.0 })
        assertNotNull(d.feed(5_020, 15_000, { t -> if (t < 5_100) 6.0 else 1.0 }, { 0.0 }, { 80.0 }))
        assertNull(d.feed(15_020, 40_000, { t -> if (t in 20_000..20_040) 6.0 else 1.0 }, { 0.0 }, { 80.0 }))
    }
}
