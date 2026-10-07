package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.EditHistory
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineTest {
    private fun bit(id: String, clipDur: Long = 10_000) = Bit("$id#0", id, clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    /** Clip a 4 s (two lines), clip b 3 s, stats 1.5 s, the loop end. */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 2_000, 4_000, listOf(CaptionLine(500, 1_800, "Arre yaar"), CaptionLine(2_200, 3_600, "paani nahi piya")), hook = true, section = 0)
        val b = ClipSegment(bit("b"), 1_000, 3_000, emptyList(), hook = false, section = 1)
        val tail = ClipSegment(a.bit, a.inMs, 600, emptyList(), hook = false, tail = true)
        return StudioPlan(listOf(a, b, StatsSegment(1_500), tail), Vibe.HYPE)
    }

    @Test
    fun `trimming the start keeps the words where they were said`() {
        val p = TimelineEdits.trimStart(plan(), 0, 1_000)
        val a = p.segments[0] as ClipSegment
        assertEquals(3_000, a.inMs)
        assertEquals(3_000, a.durMs)
        assertEquals(listOf(1_200L to 2_600L), a.lines.drop(1).map { it.startMs to it.endMs })
        // The loop end follows the new start.
        assertEquals(3_000, (p.segments.last() as ClipSegment).inMs)
        // Never past the clip's end.
        assertEquals(8_000, (TimelineEdits.trimEnd(plan(), 0, 60_000).segments[0] as ClipSegment).durMs)
    }

    @Test
    fun `split cuts a clip in two at the playhead, words go with their half`() {
        val p = TimelineEdits.split(plan(), 2_000)
        assertEquals(5, p.segments.size)
        val (x, y) = p.segments[0] as ClipSegment to p.segments[1] as ClipSegment
        assertEquals(2_000, x.durMs)
        assertEquals(4_000, y.inMs)
        assertEquals("Arre yaar", x.lines.single().text)
        assertEquals(200, y.lines.single().startMs)
        assertTrue(x.hook && !y.hook)
        assertEquals(x.section, y.section)
        assertEquals(plan().totalMs, p.totalMs)
    }

    @Test
    fun `moving and deleting keep the opening and loop right`() {
        val moved = TimelineEdits.move(plan(), 0, 1)
        assertEquals("b", (moved.segments[0] as ClipSegment).bit.momentId)
        assertTrue((moved.segments[0] as ClipSegment).hook)
        assertEquals("b", (moved.segments.last() as ClipSegment).bit.momentId)
        val deleted = TimelineEdits.delete(plan(), 0)
        assertTrue((deleted.segments[0] as ClipSegment).hook)
        // The last clip can't be deleted.
        assertEquals(deleted, TimelineEdits.delete(deleted, 0))
    }

    @Test
    fun `captions and text can be edited, moved and removed`() {
        var p = TimelineEdits.caption(plan(), 0, 0, "Arre bhai")
        assertEquals("Arre bhai", (p.segments[0] as ClipSegment).lines[0].text)
        p = TimelineEdits.nudgeCaption(p, 0, 1, 1_000)
        assertEquals(2_600, (p.segments[0] as ClipSegment).lines[1].startMs)
        p = TimelineEdits.caption(p, 0, 0, "")
        assertEquals(1, (p.segments[0] as ClipSegment).lines.size)
        p = TimelineEdits.addText(p, 1_000, "3 hours. No water.", "t1")
        assertEquals(3_500, p.texts.single().endMs)
        p = TimelineEdits.moveText(p, "t1", 100_000)
        assertEquals(p.totalMs, p.texts.single().endMs)
        p = TimelineEdits.deleteText(p, "t1")
        assertTrue(p.texts.isEmpty())
    }

    @Test
    fun `undo and redo walk the history`() {
        val h = EditHistory(plan())
        h.apply(TimelineEdits.split(h.current, 2_000))
        h.apply(TimelineEdits.volume(h.current, 1, 0f))
        assertEquals(0f, (h.current.segments[1] as ClipSegment).volume)
        h.undo()
        assertEquals(1f, (h.current.segments[1] as ClipSegment).volume)
        h.undo()
        assertEquals(plan(), h.current)
        h.redo()
        assertEquals(5, h.current.segments.size)
    }
}
