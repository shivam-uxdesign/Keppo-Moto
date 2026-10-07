package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlanner
import com.ridetrack.app.studio.StudioText
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
        // Opens straight on the hook; stats, then a split second of the hook so it loops.
        assertTrue(plan.segments.first() is ClipSegment)
        assertTrue(plan.segments[plan.segments.size - 2] is StatsSegment)
        val tail = plan.segments.last() as ClipSegment
        assertTrue(tail.tail)
        assertEquals("m9#0", tail.bit.id)
        assertEquals(StudioPlanner.TAIL_MS, tail.durMs)
        assertEquals("m9#0", plan.clips.first().bit.id)
        assertTrue(plan.clips.first().hook)
        assertTrue(plan.clips.none { it.bit.id == "m8#2" })
        // After the hook, the ride in order.
        val rest = plan.clips.drop(1).map { it.bit.atMillis }
        assertEquals(rest.sorted(), rest)
    }

    @Test
    fun `vlog tells it in order`() {
        val hook = Bit("m9#0", "m9", 19_650, 5_800, 7_700, 1_200_000, listOf(CaptionLine(260, 1_700, "Speed sixty, sixty-five hai!")), 61.0, 10f)
        val plan = StudioPlanner.plan(clip4() + hook, StudioOptions(vibe = Vibe.VLOG, lengthSec = 45, intro = false, outro = false))
        val times = plan.clips.map { it.bit.atMillis }
        assertEquals(times.sorted(), times)
        assertEquals(listOf(true) + List(plan.clips.size - 1) { false }, plan.clips.map { it.hook })
    }

    @Test
    fun `a story's moments come first, and the sign-off closes the Reel`() {
        val a = Bit("a", "ma", 20_000, 0, 3_000, 1_000_000, listOf(CaptionLine(300, 2_500, "Yo brother, take a break")), 40.0, 4f)
        val b = Bit("b", "mb", 20_000, 0, 3_000, 1_100_000, listOf(CaptionLine(300, 2_500, "sorry, cut!")), 40.0, 5f)
        val loud = Bit("x", "mx", 20_000, 0, 3_000, 1_050_000, listOf(CaptionLine(300, 2_500, "Speed sixty-five!")), 70.0, 9f)
        val bye = Bit("z", "mz", 20_000, 0, 3_000, 1_300_000, listOf(CaptionLine(300, 2_000, "Aaj ka top: 68!")), 40.0, 3f)
        val plan = StudioPlanner.plan(listOf(a, b, loud, bye), StudioOptions(vibe = Vibe.HYPE, lengthSec = 15, outro = false, loopEnd = false), story = listOf("a", "b"), ending = "z")
        val ids = plan.clips.map { it.bit.id }
        assertTrue("a" in ids && "b" in ids, "$ids")
        assertEquals("z", ids.last())
    }

    @Test
    fun `a clip from another ride goes in before the stats, and removing the hook moves the loop tail`() {
        val plan = StudioPlanner.plan(clip4(), StudioOptions(vibe = Vibe.HYPE, lengthSec = 30))
        val other = Bit("o#0", "o", 9_000, 0, 3_000, 5, listOf(CaptionLine(300, 2_400, "Chain ko lube chahiye")), 30.0, 5f, fromRide = "Mon 5 Oct")
        val added = StudioPlanner.add(plan, other)
        val i = added.segments.indexOfFirst { it is ClipSegment && it.bit.id == "o#0" }
        assertTrue(added.segments[i + 1] is StatsSegment)
        val hookAt = added.segments.indexOfFirst { it is ClipSegment && it.hook }
        val removed = StudioPlanner.remove(added, hookAt)
        val first = removed.clips.first()
        assertTrue(first.hook)
        assertEquals(first.bit.id, (removed.segments.last() as ClipSegment).bit.id)
    }

    @Test
    fun `the coach's own tips flag a slow opening and save filming tips for the next ride`() {
        val plan = StudioPlanner.plan(clip4(), StudioOptions(vibe = Vibe.HYPE, lengthSec = 30, intro = true))
        val tips = com.ridetrack.app.studio.StudioCoach.localTips(plan, StudioOptions(intro = true), clip4(), null, voiceOver = false)
        assertEquals(com.ridetrack.app.studio.TipAction.TITLE_ON_HOOK, tips.first().action)
        assertTrue(tips.any { it.nextRide })
        assertTrue(tips.size <= 4)
        val summary = com.ridetrack.app.studio.StudioCoach.summary(plan, StudioOptions(intro = true), clip4(), null, null, false, false)
        assertTrue("Yo brother" in summary)
        assertTrue("route-and-title card" in summary)
    }

    @Test
    fun `Gemini's stories, hook line, ending and tips are read safely`() {
        val d = StudioText.parseDirection(
            "{\"hook\":\"a\",\"hookLine\":\"3 hours in. No break?\",\"ending\":\"z\",\"stories\":[{\"name\":\"the hydration debate\",\"ids\":[\"a\",\"b\",\"nope\"]},{\"name\":\"one\",\"ids\":[\"a\"]}]}",
            setOf("a", "b", "z"),
        )
        assertNotNull(d)
        assertEquals("3 hours in. No break?", d.hookLine)
        assertEquals("z", d.endingId)
        assertEquals(listOf(com.ridetrack.app.studio.Story("the hydration debate", listOf("a", "b"))), d.stories)
        val tips = StudioText.parseTips("{\"tips\":[{\"text\":\"Open on the line\",\"action\":\"title_on_hook\"},{\"text\":\"Film the road\",\"action\":\"none\",\"nextRide\":true},{\"text\":\"\"}]}")
        assertEquals(2, tips.size)
        assertEquals(com.ridetrack.app.studio.TipAction.TITLE_ON_HOOK, tips[0].action)
        assertTrue(tips[1].nextRide)
        assertEquals(emptyList(), StudioText.parseTips("not json"))
    }

    @Test
    fun `with a song, talking clips end on the song's beat`() {
        val plan = StudioPlanner.plan(clip4(), StudioOptions(vibe = Vibe.HYPE, lengthSec = 15, outro = false, loopEnd = false, bpm = 100))
        plan.clips.forEach { assertEquals(0, it.durMs % 600, "${it.durMs}") }
        assertTrue(com.ridetrack.app.studio.MusicLibrary.TRACKS.size >= 16)
        Vibe.entries.forEach { assertTrue(com.ridetrack.app.studio.MusicLibrary.forVibe(it).size >= 3, "$it") }
    }

    @Test
    fun `only lengths the clips can fill are offered`() {
        assertEquals(listOf(15), StudioPlanner.lengthsFor(clip4(), Vibe.HYPE))
        assertEquals(listOf(15, 30, 45, 60), StudioPlanner.lengthsFor(List(20) { i -> clip4()[0].copy(id = "b$i") }, Vibe.HYPE))
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
