package com.ridetrack.app

import com.ridetrack.app.studio.EditorRide
import com.ridetrack.app.studio.RideMark
import com.ridetrack.telemetry.model.TelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRideTest {
    private fun s(t: Long, kmh: Double, g: Double = 0.0, lean: Double = 0.0) =
        TelemetrySample(t, null, null, kmh / 3.6, null, null, g, null, lean, null)

    @Test fun `a clip's speed line and its moments, over the whole recording`() {
        val start = 1_000_000L
        val samples = (0..200).map { k ->
            val t = start + k * 100L
            when (k) {
                in 60..65 -> s(t, 50.0, g = -0.6)  // braking at 6 s
                in 120..124 -> s(t, 70.0, lean = -31.0) // a left lean at 12 s
                150 -> s(t, 92.0)                    // top speed at 15 s
                else -> s(t, 60.0)
            }
        }
        val tr = EditorRide.trace(samples, start, 20_000, downshifts = listOf(3_000))
        assertTrue(tr.speed.size > 70)
        assertEquals(listOf(RideMark.Kind.DOWNSHIFT, RideMark.Kind.BRAKING, RideMark.Kind.LEAN, RideMark.Kind.TOP), tr.marks.map { it.kind })
        assertEquals("Lean · 31° L", tr.marks[2].label)
        assertEquals(15_000L, tr.marks[3].clipMs)
    }
}
