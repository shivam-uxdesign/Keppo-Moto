package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipColor
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ClipTools
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Speed
import com.ridetrack.app.studio.SpeedRamp
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.TrackEdits
import com.ridetrack.app.studio.Transition
import com.ridetrack.app.studio.TransitionKind
import com.ridetrack.app.studio.TransitionLength
import com.ridetrack.app.studio.Vibe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipToolsTest {
    private fun bit(id: String, clipDur: Long = 10_000) = Bit("$id#0", id, clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    /** Clip a 4 s (talking), clip b 3 s, stats, the loop end. */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 2_000, 4_000, listOf(CaptionLine(1_000, 2_000, "Arre yaar")), hook = true)
        val b = ClipSegment(bit("b"), 1_000, 3_000, emptyList(), hook = false)
        val tail = ClipSegment(a.bit, a.inMs, 600, emptyList(), hook = false, tail = true)
        return StudioPlan(listOf(a, b, StatsSegment(1_500), tail), Vibe.HYPE)
    }

    private fun seg(p: StudioPlan, i: Int) = p.segments[i] as ClipSegment

    @Test
    fun `half speed plays the same part twice as long, words with it`() {
        val p = ClipTools.speed(plan(), 0, 0.5f)
        val a = seg(p, 0)
        assertEquals(8_000, a.durMs)
        assertEquals(4_000, a.sourceMs)
        assertEquals(2_000L to 4_000L, a.lines.single().let { it.startMs to it.endMs })
        // Double speed: half as long.
        assertEquals(2_000, seg(ClipTools.speed(plan(), 0, 2f), 0).durMs)
    }

    @Test
    fun `a ramp keeps the same part of the clip`() {
        val p = ClipTools.ramp(plan(), 1, SpeedRamp.MIDDLE)
        val b = seg(p, 1)
        assertTrue(abs(b.sourceMs - 3_000) <= 2, "${b.sourceMs}")
        // 0.3/1.6 + 0.4/0.4 + 0.3/1.6 of 3 s.
        assertTrue(abs((3_000 * (0.3 / 1.6 + 1.0 + 0.3 / 1.6)).toLong() - b.durMs) <= 2, "${b.durMs}")
        assertEquals(0.4f * 1f, Speed.at(1f, SpeedRamp.MIDDLE, 0.5f))
    }

    @Test
    fun `trimming a sped-up clip moves through the clip at its speed`() {
        val p = TimelineEdits.trimStart(ClipTools.speed(plan(), 0, 2f), 0, 500)
        val a = seg(p, 0)
        assertEquals(3_000, a.inMs)
        assertEquals(1_500, a.durMs)
        // The end can't go past the clip: 8 s left of it at 2x is 4 s.
        assertEquals(4_000, seg(TimelineEdits.trimEnd(ClipTools.speed(plan(), 0, 2f), 0, 60_000), 0).durMs)
    }

    @Test
    fun `duplicate, replace and slip`() {
        val d = ClipTools.duplicate(plan(), 1)
        assertEquals(listOf("a", "b", "b"), d.clips.map { it.bit.momentId })
        val r = ClipTools.replace(plan(), 1, bit("c", 2_000))
        assertEquals("c", seg(r, 1).bit.momentId)
        // c is only 2 s long.
        assertEquals(2_000, seg(r, 1).durMs)
        val s = ClipTools.slip(plan(), 0, 1_500)
        assertEquals(3_500, seg(s, 0).inMs)
        assertEquals(4_000, seg(s, 0).durMs)
        // The words move back with the picture.
        assertEquals(-500L, seg(s, 0).lines.firstOrNull()?.startMs ?: -500L)
        // Never past the clip's end.
        assertEquals(6_000, seg(ClipTools.slip(plan(), 0, 60_000), 0).inMs)
    }

    @Test
    fun `freeze holds a frame between the two halves`() {
        val p = ClipTools.freeze(plan(), 2_000, "/f.jpg", 1_500)
        assertEquals(listOf(2_000L, 1_500L, 2_000L), p.segments.take(3).map { it.durMs })
        val hold = seg(p, 1)
        assertEquals("/f.jpg", hold.still)
        assertEquals(4_000, hold.inMs)
        assertEquals(0f, hold.volume)
        assertEquals(TransitionKind.CUT, hold.transition?.kind)
        // A freeze frame trims as a length only.
        assertEquals(1_000, seg(TimelineEdits.trimStart(p, 1, 500), 1).durMs)
    }

    @Test
    fun `reframe sets a crop, then moves between keys`() {
        val crop = ClipTools.reframe(plan(), 0, 0, dZoom = 0.3f, fixed = true)
        assertEquals(1, seg(crop, 0).frame.size)
        assertEquals(1.3f, ClipTools.frameAt(seg(crop, 0), 3_000).zoom)
        val move = ClipTools.reframe(plan(), 0, 2_000, dZoom = 0.5f, dx = 0.4f)
        val a = seg(move, 0)
        assertEquals(listOf(0L, 2_000L), a.frame.map { it.atMs })
        val mid = ClipTools.frameAt(a, 1_000)
        assertTrue(abs(mid.zoom - 1.25f) < 0.001f && abs(mid.x - 0.2f) < 0.001f, "$mid")
        // Punch-in on each line.
        val punch = seg(ClipTools.punchIn(plan(), 0), 0)
        assertTrue(punch.frame.any { it.zoom > 1.1f })
    }

    @Test
    fun `transitions per cut and on all cuts`() {
        val t = Transition(TransitionKind.WHIP, TransitionLength.SHORT)
        val one = ClipTools.transition(plan(), 1, t)
        assertEquals(t, seg(one, 1).transition)
        val all = ClipTools.transitionAll(plan(), Transition(TransitionKind.FADE))
        assertNull(seg(all, 0).transition)
        assertEquals(TransitionKind.FADE, seg(all, 1).transition?.kind)
        // The loop end isn't a cut you choose.
        assertNull(seg(all, 3).transition)
        assertEquals((Vibe.HYPE.transitionMs * 0.5f).toLong(), t.ms(Vibe.HYPE))
    }

    @Test
    fun `pasting settings brings the speed, turn and colour`() {
        var p = ClipTools.speed(plan(), 0, 2f)
        p = ClipTools.rotate(p, 0)
        p = ClipTools.color(p, 0) { it.copy(warmth = 0.4f) }
        val s = TrackEdits.copySettings(p, 0)!!
        val pasted = TrackEdits.pasteSettings(p, setOf(1), s)
        val b = seg(pasted, 1)
        assertEquals(2f, b.speed)
        assertEquals(1_500, b.durMs)
        assertEquals(90, b.rotation)
        assertEquals(0.4f, b.color.warmth)
    }

    @Test
    fun `clip tools are saved with the Reel`() {
        var plan = ClipTools.ramp(ClipTools.speed(plan(), 0, 1.5f), 0, SpeedRamp.EASE_OUT)
        plan = ClipTools.flip(ClipTools.rotate(plan, 1), 1)
        plan = ClipTools.reframe(plan, 1, 1_000, dZoom = 0.2f, dy = -0.3f)
        plan = ClipTools.color(plan, 1) { ClipColor(0.1f, -0.2f, 0.3f, -0.4f, look = false) }
        plan = ClipTools.transition(plan, 1, Transition(TransitionKind.GLITCH, TransitionLength.LONG))
        plan = ClipTools.reverse(plan, 0, "/r.mp4")
        plan = ClipTools.freeze(plan, 500, "/f.jpg")
        val p = ReelProject(
            id = "r", rideId = null, createdAt = 1, updatedAt = 1, title = "t", series = "", episode = 1, hookLine = "", postCaption = "", story = null,
            options = StudioOptions(), musicUri = null, musicName = null, plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs,
        )
        assertEquals(p, ReelJson.read(ReelJson.write(p)))
    }
}
