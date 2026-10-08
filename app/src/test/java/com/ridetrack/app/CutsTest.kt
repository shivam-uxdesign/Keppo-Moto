package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ClipTools
import com.ridetrack.app.studio.Cuts
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.Transition
import com.ridetrack.app.studio.TransitionKind
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutsTest {
    private fun bit(id: String) = Bit("$id#0", id, 10_000, 0, 10_000, 1_000_000, emptyList(), 40.0, 3f)

    /** a and b in the same section, c in the next, then stats and the loop end. */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 0, 3_000, emptyList(), hook = true, section = 0)
        val b = ClipSegment(bit("b"), 0, 2_000, emptyList(), hook = false, section = 0)
        val c = ClipSegment(bit("c"), 0, 4_000, emptyList(), hook = false, section = 1)
        val tail = ClipSegment(a.bit, 0, 600, emptyList(), hook = false, tail = true)
        return StudioPlan(listOf(a, b, c, StatsSegment(1_500), tail), Vibe.HYPE)
    }

    @Test fun `transitions play between sections, not inside one`() {
        val p = plan()
        assertFalse(Cuts.plays(p, 1))
        assertTrue(Cuts.plays(p, 2))
        assertEquals(listOf(2, 3, 4), Cuts.marks(p).map { it.segment })
        assertEquals(5_000L, Cuts.marks(p).first().atMs)
    }

    @Test fun `a chosen transition shows inside a section, a chosen cut hides one`() {
        val p = ClipTools.transition(ClipTools.transition(plan(), 1, Transition(TransitionKind.FLASH)), 2, Transition(TransitionKind.CUT))
        val marks = Cuts.marks(p)
        assertEquals(listOf(1, 3, 4), marks.map { it.segment })
        assertTrue(marks.first().chosen)
        assertEquals(TransitionKind.FLASH, marks.first().kind)
    }

    @Test fun `the frame knows the cut on each side`() {
        val f = Cuts.frameAt(plan(), 2, 100, 4_000)
        assertTrue(f.hasPrev)
        assertTrue(f.hasNext)
        assertFalse(Cuts.frameAt(plan(), 1, 100, 2_000).hasPrev)
    }
}
