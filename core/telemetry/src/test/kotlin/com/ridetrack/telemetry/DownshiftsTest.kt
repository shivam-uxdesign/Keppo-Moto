package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.Downshifts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownshiftsTest {
    private val hop = 100L

    /** [hz] for each 100 ms step. */
    private fun pitches(seconds: Int, hz: (Long) -> Float) = FloatArray(seconds * 10) { hz(it * hop) }

    @Test fun `slowing down with the pitch jumping up is a downshift`() {
        // 60 Hz, dropping a gear at 3 s to 78 Hz (+30%); speed falling 20 → 12 m/s.
        val p = pitches(6) { t -> if (t < 3_000) 60f else 78f }
        val found = Downshifts.find(p, hop) { t -> 20.0 - t / 750.0 }
        assertEquals(1, found.size)
        assertTrue(found[0] in 2_900L..3_100L)
    }

    @Test fun `revving up while speeding up is not`() {
        val p = pitches(6) { t -> if (t < 3_000) 60f else 78f }
        assertTrue(Downshifts.find(p, hop) { t -> 10.0 + t / 750.0 }.isEmpty())
    }

    @Test fun `stopped, or a pitch misread an octave up, is not`() {
        val p = pitches(6) { t -> if (t < 3_000) 60f else 78f }
        assertTrue(Downshifts.find(p, hop) { 1.0 }.isEmpty())
        val octave = pitches(6) { t -> if (t < 3_000) 60f else 150f }
        assertTrue(Downshifts.find(octave, hop) { t -> 20.0 - t / 750.0 }.isEmpty())
    }

    @Test fun `two gears down while braking are two, a quick double blip is one`() {
        // 60 → 78 at 2 s, → 100 at 4.5 s, while slowing 25 → 10 m/s.
        val p = pitches(7) { t -> when { t < 2_000 -> 60f; t < 4_500 -> 78f; else -> 100f } }
        assertEquals(2, Downshifts.find(p, hop) { t -> 25.0 - t / 470.0 }.size)
        // 60 → 78 → 60 → 78 within a second: one.
        val blips = pitches(6) { t -> when { t < 2_000 -> 60f; t < 2_400 -> 78f; t < 2_700 -> 60f; else -> 78f } }
        assertEquals(1, Downshifts.find(blips, hop) { t -> 20.0 - t / 750.0 }.size)
    }
}
