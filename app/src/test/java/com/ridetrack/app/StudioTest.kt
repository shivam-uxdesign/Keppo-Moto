package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlanner
import com.ridetrack.app.studio.StudioText
import com.ridetrack.app.studio.TitleSegment
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioTest {
    private val speed = { _: Long -> 60.0 }

    /** The 6 Oct ride: clip 4 (three lines) and clip 9 ("speed sixty-five"), as timed by the transcriber. */
    private fun clip4() = StudioPlanner.bitsOf(
        "m4", 1_000_000, 23_830,
        listOf(
            CaptionLine(540, 1_400, "Ek chhota sa pop-up"),
            CaptionLine(2_180, 3_900, "Yo brother, take a break!"),
            CaptionLine(4_480, 6_100, "Teen ghante se chala raha hai tu"),
            CaptionLine(14_750, 16_900, "Actually nahi, hydration breaks nahi chahiye"),
        ),
        speed,
    )

    @Test
    fun `speech becomes bits split at pauses, with captions timed from the bit`() {
        val bits = clip4()
        assertEquals(2, bits.size)
        val a = bits[0]
        assertEquals(240, a.inMs)
        assertEquals(6_450, a.outMs)
        assertEquals(3, a.lines.size)
        assertEquals(300, a.lines[0].startMs)
        assertEquals(1_000_240, a.atMillis)
        assertEquals(1, bits[1].lines.size)
    }

    @Test
    fun `a clip with no speech gives a few seconds of riding around the event`() {
        val bits = StudioPlanner.bitsOf("m1", 0, 7_920, emptyList(), speed, focusMs = 2_000)
        assertEquals(1, bits.size)
        assertEquals(0, bits[0].inMs)
        assertEquals(4_000, bits[0].outMs)
        assertTrue(bits[0].lines.isEmpty())
    }

    @Test
    fun `talking bits run to the end of the sentence on the beat`() {
        val b = clip4()[0]
        val d = StudioPlanner.durationOf(b, Vibe.HYPE, 0.5)
        assertTrue(d >= b.lines.last().endMs, "$d")
        assertEquals(0, d % Vibe.HYPE.beatMs)
    }

    @Test
    fun `the plan fits the length, opens on the hook and never uses the same seconds twice`() {
        val hook = Bit("m9#0", "m9", 19_650, 5_800, 7_700, 1_200_000, listOf(CaptionLine(260, 1_700, "Speed sixty, sixty-five hai!")), 61.0, 10f)
        // Clip 8 was filmed over the same seconds as clip 9.
        val overlap = Bit("m8#2", "m8", 25_730, 21_400, 23_600, 1_199_900, listOf(CaptionLine(240, 1_900, "Speed sixty, sixty-five hai")), 61.0, 6f)
        val bits = clip4() + listOf(hook, overlap) + StudioPlanner.bitsOf("m1", 900_000, 7_920, emptyList(), speed)
        val plan = StudioPlanner.plan(bits, StudioOptions(vibe = Vibe.HYPE, lengthSec = 30))
        assertTrue(plan.totalMs <= 30_000, "${plan.totalMs}")
        assertTrue(plan.segments.first() is TitleSegment)
        assertTrue(plan.segments.last() is StatsSegment)
        assertEquals("m9#0", plan.clips.first().bit.id)
        assertTrue(plan.clips.first().hook)
        assertTrue(plan.clips.none { it.bit.id == "m8#2" })
        // After the hook, the ride in order.
        val rest = plan.clips.drop(1).map { it.bit.atMillis }
        assertEquals(rest.sorted(), rest)
    }

    @Test
    fun `vlog tells it in order with no hook`() {
        val hook = Bit("m9#0", "m9", 19_650, 5_800, 7_700, 1_200_000, listOf(CaptionLine(260, 1_700, "Speed sixty, sixty-five hai!")), 61.0, 10f)
        val plan = StudioPlanner.plan(clip4() + hook, StudioOptions(vibe = Vibe.VLOG, lengthSec = 45, intro = false, outro = false))
        val times = plan.clips.map { it.bit.atMillis }
        assertEquals(times.sorted(), times)
        assertTrue(plan.clips.none { it.hook })
    }

    @Test
    fun `only lengths the clips can fill are offered`() {
        assertEquals(listOf(30), StudioPlanner.lengthsFor(clip4(), Vibe.HYPE))
        assertEquals(listOf(30, 45, 60), StudioPlanner.lengthsFor(List(20) { i -> clip4()[0].copy(id = "b$i") }, Vibe.HYPE))
    }

    @Test
    fun `nudging the start keeps captions where they were said, and text edits keep the timing`() {
        val plan = StudioPlanner.plan(clip4(), StudioOptions(vibe = Vibe.CINE, lengthSec = 30, intro = false, outro = false))
        val i = plan.segments.indexOfFirst { it is ClipSegment }
        val before = plan.segments[i] as ClipSegment
        val after = StudioPlanner.nudge(plan, i, 500, 0).segments[i] as ClipSegment
        assertEquals(before.inMs + 500, after.inMs)
        assertEquals(before.lines.last().startMs - 500, after.lines.last().startMs)
        val edited = StudioPlanner.setText(plan, i, "Bhai, break lete hain").segments[i] as ClipSegment
        assertEquals(1, edited.lines.size)
        assertEquals(before.lines.first().startMs, edited.lines[0].startMs)
        assertEquals(before.lines.last().endMs, edited.lines[0].endMs)
    }

    @Test
    fun `Gemini's timed lines are read from JSON, fenced or not, in seconds or m colon ss`() {
        val reply = "```json\n{\"lines\":[{\"start\":1.2,\"end\":\"0:03.5\",\"text\":\"Yo brother\"},{\"start\":4,\"end\":3,\"text\":\"bad\"},{\"start\":5,\"end\":6,\"text\":\"\"}]}\n```"
        val lines = StudioText.parseLines(reply)
        assertNotNull(lines)
        assertEquals(listOf(CaptionLine(1_200, 3_500, "Yo brother")), lines)
        assertEquals(emptyList(), StudioText.parseLines("{\"lines\":[]}"))
        assertNull(StudioText.parseLines("Sorry, I can't help"))
    }

    @Test
    fun `the director's reply keeps only known moments`() {
        val d = StudioText.parseDirection("{\"title\":\"Sixty-five, steady\",\"caption\":\"Evening run\",\"hook\":\"m9#0\",\"scores\":{\"m9#0\":9,\"x\":7,\"m4#0\":14}}", setOf("m9#0", "m4#0"))
        assertNotNull(d)
        assertEquals("m9#0", d.hookId)
        assertEquals(mapOf("m9#0" to 9f, "m4#0" to 10f), d.punch)
        assertNull(StudioText.parseDirection("{\"hook\":\"zz\"}", setOf("a"))?.hookId)
    }

    @Test
    fun `the song dips while the rider talks, with short ramps`() {
        val g = com.ridetrack.app.studio.GainProcessor.ducking(listOf(1_000L..2_000L), 0.25f)
        assertEquals(1f, g(0))
        assertEquals(0.25f, g(1_500_000))
        val ramp = g(925_000)
        assertTrue(ramp in 0.25f..1f && ramp < 1f, "$ramp")
        assertEquals(1f, g(2_500_000))
    }
}
