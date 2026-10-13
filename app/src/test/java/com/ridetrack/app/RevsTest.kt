package com.ridetrack.app

import com.ridetrack.app.studio.Revs
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevsTest {
    private val rate = 8_000

    /** An engine-like buzz whose pitch goes from [from] to [to] Hz over [secs]. */
    private fun engine(from: Float, to: Float, secs: Float): ShortArray {
        val n = (rate * secs).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val f = from + (to - from) * i / n
            phase += 2 * PI * f / rate
            // A fundamental with a couple of harmonics, like a firing engine.
            (6_000 * (sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase))).toInt().toShort()
        }
    }

    @Test
    fun `the engine's pitch is found and follows the revs`() {
        val p = Revs.pitches(engine(60f, 180f, 4f), rate)
        val heard = p.filter { it > 0f }
        assertTrue(heard.size > p.size * 3 / 4, "heard ${heard.size} of ${p.size}")
        assertTrue(abs(heard.first() - 62f) < 8f, "starts near 60 Hz: ${heard.first()}")
        assertTrue(abs(heard.last() - 175f) < 15f, "ends near 180 Hz: ${heard.last()}")
    }

    @Test
    fun `silence has no pitch`() {
        assertTrue(Revs.pitches(ShortArray(rate * 2), rate).all { it == 0f })
    }

    @Test
    fun `levels are relative to the bike's own range, and the top reads as the redline`() {
        val cruise = Revs.pitches(engine(70f, 80f, 3f), rate)
        val pull = Revs.pitches(engine(80f, 200f, 3f), rate)
        val range = assertNotNull(Revs.range(listOf(cruise, pull)))
        val top = assertNotNull(Revs.level(pull, range, (pull.size - 2) * Revs.HOP_MS))
        assertTrue(top >= Revs.REDLINE, "near the top: $top")
        val low = assertNotNull(Revs.level(cruise, range, 1_000))
        assertTrue(low < 0.3f, "cruising: $low")
        assertNull(Revs.range(listOf(FloatArray(5) { 100f })))
        assertEquals(null, Revs.level(FloatArray(3), range, 100))
    }

    @Test
    fun `the light is off below the redline, pulses above it, and goes off at the shift`() {
        // Cruising until 1 s, at the limiter 1–3 s, shifted (low) after.
        val level = { t: Long -> if (t < 1_000) 0.5f else if (t < 3_000) 1f else 0.6f }
        assertEquals(0f, Revs.pulse(level, 900))
        val lit = (1_200L..2_900L step 20).map { Revs.pulse(level, it) }
        assertTrue(lit.max() > 0.9f && lit.min() < 0.1f, "pulses: ${lit.min()}..${lit.max()}")
        assertTrue(Revs.pulse(level, 3_300) < 0.01f)
        // A tag reads as climbing from the line to the limiter.
        val tag = com.ridetrack.app.studio.RedlineTag("r", 1_000, 3_000)
        assertEquals(Revs.REDLINE, Revs.tagLevel(tag, 1_000))
        assertNull(Revs.tagLevel(tag, 3_000))
    }

    @Test
    fun `tags join when they touch and stay inside the Reel`() {
        val bit = com.ridetrack.app.studio.Bit("a#0", "a", 5_000, 0, 5_000, 1_000_000, emptyList(), 40.0, 3f)
        val seg = com.ridetrack.app.studio.ClipSegment(bit, 0, 5_000, emptyList(), hook = true)
        var p = com.ridetrack.app.studio.StudioPlan(listOf(seg), com.ridetrack.app.studio.Vibe.HYPE)
        p = com.ridetrack.app.studio.TrackEdits.tagRedline(p, 1_000, "r1")
        p = com.ridetrack.app.studio.TrackEdits.tagRedline(p, 2_500, "r2")
        assertEquals(listOf(1_000L to 4_500L), p.redlines.map { it.startMs to it.endMs })
        p = com.ridetrack.app.studio.TrackEdits.tagRedline(p, 4_800, "r3")
        assertEquals(5_000L, p.redlines.last().endMs)
    }
}
