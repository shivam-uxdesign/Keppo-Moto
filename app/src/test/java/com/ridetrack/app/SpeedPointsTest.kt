package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ClipTools
import com.ridetrack.app.studio.RideMark
import com.ridetrack.app.studio.SpeedPoint
import com.ridetrack.app.studio.SpeedPoints
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedPointsTest {
    private fun bit(clipDur: Long = 20_000) = Bit("a#0", "a", clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    /** One clip: 10 s of its recording from 5 s in. */
    private fun plan() = StudioPlan(listOf(ClipSegment(bit(), 5_000, 10_000, emptyList(), hook = true)), Vibe.HYPE)

    @Test
    fun `the speed glides in a line between points`() {
        val pts = listOf(SpeedPoint(0f, 1f), SpeedPoint(0.5f, 0.5f), SpeedPoint(1f, 2f))
        assertEquals(1f, SpeedPoints.at(pts, 0f))
        assertEquals(0.75f, SpeedPoints.at(pts, 0.25f), 0.001f)
        assertEquals(2f, SpeedPoints.at(pts, 1f))
    }

    @Test
    fun `a curve changes the clip's length but plays the same part`() {
        val slow = ClipTools.speedPoints(plan(), 0, listOf(SpeedPoint(0f, 0.5f), SpeedPoint(1f, 0.5f)))
        val c = slow.segments[0] as ClipSegment
        // Half speed throughout: twice as long, the same 10 s of the recording.
        assertTrue(kotlin.math.abs(c.durMs - 20_000) <= 50)
        assertTrue(kotlin.math.abs(c.sourceMs - 10_000) <= 50)
        // Back to steady: the length comes back.
        val back = ClipTools.speedPoints(slow, 0, emptyList()).segments[0] as ClipSegment
        assertTrue(kotlin.math.abs(back.durMs - 10_000) <= 50)
    }

    @Test
    fun `points are kept in order, inside the range, with both ends`() {
        val pts = SpeedPoints.clean(listOf(SpeedPoint(0.7f, 9f), SpeedPoint(0.2f, 0.1f)))
        assertEquals(listOf(0f, 0.2f, 0.7f, 1f), pts.map { it.at })
        assertEquals(SpeedPoints.MIN, pts[1].speed)
        assertEquals(SpeedPoints.MAX, pts[2].speed)
    }

    @Test
    fun `slow at the lean puts the slow part on the lean`() {
        // The lean is 10 s into the recording: halfway through the part (5 s to 15 s).
        val marks = listOf(RideMark(RideMark.Kind.LEAN, 10_000, "Lean 38°"))
        val pts = SpeedPoints.preset(SpeedPoints.Preset.LEAN, marks, 5_000, 10_000)!!
        assertEquals(0.5f, SpeedPoints.at(pts, 0.5f), 0.01f)
        assertEquals(1f, SpeedPoints.at(pts, 0.1f), 0.01f)
        assertEquals(1f, SpeedPoints.at(pts, 0.9f), 0.01f)
        // No lean in the part: not offered.
        assertNull(SpeedPoints.preset(SpeedPoints.Preset.LEAN, listOf(RideMark(RideMark.Kind.LEAN, 18_000, "")), 5_000, 10_000))
    }

    @Test
    fun `fast straights speeds up everywhere but the moments`() {
        val marks = listOf(RideMark(RideMark.Kind.DOWNSHIFT, 12_000, "Downshift"))
        val pts = SpeedPoints.preset(SpeedPoints.Preset.STRAIGHTS, marks, 5_000, 10_000)!!
        assertEquals(2f, SpeedPoints.at(pts, 0.1f), 0.01f)
        assertEquals(1f, SpeedPoints.at(pts, 0.7f), 0.01f)
        assertTrue(pts.all { it.speed in SpeedPoints.MIN..SpeedPoints.MAX })
    }

    @Test
    fun `downshifts each get their own slow part`() {
        val marks = listOf(RideMark(RideMark.Kind.DOWNSHIFT, 7_000, ""), RideMark(RideMark.Kind.DOWNSHIFT, 13_000, ""))
        val pts = SpeedPoints.preset(SpeedPoints.Preset.DOWNSHIFT, marks, 5_000, 10_000)!!
        assertEquals(0.5f, SpeedPoints.at(pts, 0.2f), 0.01f)
        assertEquals(0.5f, SpeedPoints.at(pts, 0.8f), 0.01f)
        assertEquals(1f, SpeedPoints.at(pts, 0.5f), 0.01f)
    }
}
