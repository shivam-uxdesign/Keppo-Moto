package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.SpeechGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechGateTest {
    /** 46 ms chunks, like 2048 samples at 44.1 kHz. */
    private fun SpeechGate.feed(from: Long, to: Long, db: (Long) -> Float, threshold: Float = -30f): Boolean {
        var any = false
        var t = from
        while (t < to) {
            any = onLevel(t, db(t), 46, threshold) || any
            t += 46
        }
        return any
    }

    @Test
    fun `a single knock is not speech`() {
        val g = SpeechGate()
        assertFalse(g.feed(0, 2_000, { t -> if (t in 460..600) -10f else -50f }))
        assertNull(g.lastSpeechMillis)
    }

    @Test
    fun `talking for a moment is speech, and the last time is kept`() {
        val g = SpeechGate()
        assertTrue(g.feed(0, 3_000, { t -> if (t in 1_000..2_000) -20f else -50f }))
        val last = g.lastSpeechMillis!!
        assertTrue(last in 1_900..2_100)
        g.feed(3_000, 5_000, { -50f })
        assertFalse(g.speaking)
        assertEquals(last, g.lastSpeechMillis)
    }

    @Test
    fun `the threshold decides`() {
        val g = SpeechGate()
        assertFalse(g.feed(0, 2_000, { -35f }, threshold = -30f))
        assertTrue(g.feed(2_000, 4_000, { -35f }, threshold = -40f))
    }
}
