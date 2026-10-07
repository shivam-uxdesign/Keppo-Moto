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
    fun `several clips' captions come back from one reply, by clip number`() {
        val reply = "{\"clips\":[{\"clip\":2,\"lines\":[{\"start\":0.5,\"end\":1.5,\"text\":\"sorry, cut!\"}]},{\"clip\":1,\"lines\":[]},{\"clip\":9,\"lines\":[]}]}"
        val out = StudioText.parseBatch(reply, 3)
        assertEquals(emptyList(), out[0])
        assertEquals(listOf(CaptionLine(500, 1_500, "sorry, cut!")), out[1])
        assertNull(out[2])
        assertEquals(listOf(null, null), StudioText.parseBatch("nope", 2))
    }

    @Test
    fun `Google's retry time is read, so a spent model isn't asked again until then`() {
        val q = com.ridetrack.app.transcribe.GeminiQuota
        assertEquals(((18 * 60 + 46) * 60 + 11.7) * 1000, q.retryAfterMs("Quota exceeded ... Please retry in 18h46m11.73350604s.")!!.toDouble(), 100.0)
        assertEquals(67_000L, q.retryAfterMs("\"retryDelay\": \"67s\""))
        assertNull(q.retryAfterMs("no time here"))
        val msg = com.ridetrack.app.transcribe.FirebaseTranscriber.busyMessage(
            com.ridetrack.app.transcribe.FirebaseTranscriber.Busy("Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20. Please retry in 18h46m11s."),
        )
        assertTrue("daily limit" in msg && "19 h" in msg, msg)
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

    @Test
    fun `a saved Reel reopens exactly as it was saved`() {
        val plan = StudioPlanner.plan(clip4(), StudioOptions(vibe = Vibe.CINE, lengthSec = 30, intro = true))
        val p = com.ridetrack.app.studio.ReelProject(
            id = "abc", rideId = "ride1", createdAt = 1, updatedAt = 2, title = "Tuesday Evening Ride", series = "Diaries", episode = 4,
            hookLine = "3 hours in. No break?", postCaption = "Evening run", story = "the hydration debate",
            options = StudioOptions(vibe = Vibe.CINE, lengthSec = 30, intro = true, seed = 3), musicUri = null, musicName = null, plan = plan,
            takes = listOf(com.ridetrack.app.studio.SavedTake(1_000, 2_500, "voice-0.pcm", listOf(CaptionLine(0, 900, "Airport loop")))),
            tips = listOf(com.ridetrack.app.studio.Tip("Film the road", nextRide = true)), durationMs = plan.totalMs,
            inJournal = true, coverAtMs = 1_200, coverLine = "Sixty-five!",
        )
        val back = com.ridetrack.app.studio.ReelJson.read(com.ridetrack.app.studio.ReelJson.write(p))
        assertEquals(p, back)
        assertNull(com.ridetrack.app.studio.ReelJson.read("not json"))
    }

    @Test
    fun `cover frames map Reel time to the source clip and favour the hook`() {
        val b = clip4()
        val plan = com.ridetrack.app.studio.StudioPlan(
            listOf(
                com.ridetrack.app.studio.TitleSegment(2_000),
                com.ridetrack.app.studio.ClipSegment(b[0], 1_000, 4_000, emptyList(), hook = false),
                com.ridetrack.app.studio.ClipSegment(b[1], 14_000, 3_000, emptyList(), hook = true),
                com.ridetrack.app.studio.ClipSegment(b[0], 1_000, 600, emptyList(), hook = false, tail = true),
            ),
            com.ridetrack.app.studio.Vibe.HYPE,
        )
        // 3 s into the Reel is 1 s into the first clip segment.
        val spot = com.ridetrack.app.studio.ReelCover.spotAt(plan, 3_000)!!
        assertEquals(2_000, spot.sourceMs)
        // The title maps to the nearest clip; the tail is never used.
        assertEquals(1_000, com.ridetrack.app.studio.ReelCover.spotAt(plan, 500)!!.sourceMs)
        assertEquals(b[1], com.ridetrack.app.studio.ReelCover.spotAt(plan, 9_400)!!.segment.bit)
        val c = com.ridetrack.app.studio.ReelCover.candidates(plan, 2)
        assertEquals(listOf(7_000L, 8_000L), c)
    }

    private fun rideBits(): List<com.ridetrack.app.studio.Bit> {
        var t = 1_000_000L
        return (0 until 8).flatMap { k ->
            val lines = if (k % 2 == 0) listOf(CaptionLine(400, 2_400, if (k == 4) "Arre oops, gir gaya!" else "Line number $k is here")) else emptyList()
            val kmh = 20.0 + k * 8
            StudioPlanner.bitsOf("m$k", t, 10_000, lines, { kmh }).also { t += 60_000 }
        }
    }

    @Test
    fun `one ride gives several Reel ideas, clips reused across them`() {
        val bits = rideBits()
        val ideas = com.ridetrack.app.studio.ReelIdeas.ideas(bits, null, com.ridetrack.app.studio.StudioOptions())
        val kinds = ideas.map { it.kind }
        assertEquals(
            listOf(com.ridetrack.app.studio.IdeaKind.HIGHLIGHTS, com.ridetrack.app.studio.IdeaKind.HOOK, com.ridetrack.app.studio.IdeaKind.SPEED_RUN, com.ridetrack.app.studio.IdeaKind.BLOOPERS),
            kinds,
        )
        val hook = ideas.first { it.kind == com.ridetrack.app.studio.IdeaKind.HOOK }
        assertTrue(hook.plan.totalMs <= 15_000)
        assertTrue(hook.plan.clips.first().bit.talking)
        val speed = ideas.first { it.kind == com.ridetrack.app.studio.IdeaKind.SPEED_RUN }
        assertEquals(com.ridetrack.app.studio.Vibe.HYPE, speed.options.vibe)
        assertEquals("m7", speed.plan.clips.first().bit.momentId)
        val oops = ideas.first { it.kind == com.ridetrack.app.studio.IdeaKind.BLOOPERS }
        assertTrue(oops.plan.clips.any { it.bit.momentId == "m4" })
        // Pending Gemini: a "The story" idea first.
        assertEquals(com.ridetrack.app.studio.IdeaKind.STORY, com.ridetrack.app.studio.ReelIdeas.ideas(bits, null, com.ridetrack.app.studio.StudioOptions(), geminiPending = true).first().kind)
    }

    @Test
    fun `a teaser flashes a later clip first and isn't counted as a clip`() {
        val plan = StudioPlanner.plan(rideBits(), com.ridetrack.app.studio.StudioOptions(lengthSec = 30, teaser = true))
        val first = plan.segments.first() as com.ridetrack.app.studio.ClipSegment
        assertTrue(first.teaser)
        assertEquals(StudioPlanner.TEASER_MS, first.durMs)
        assertTrue(plan.clips.none { it.teaser })
        assertTrue(plan.clips.drop(1).any { it.bit.id == first.bit.id })
        assertTrue(plan.clips.first().hook)
        assertTrue(plan.totalMs <= 30_000)
    }

    @Test
    fun `phone video dates are read from the container, missing ones are unknown`() {
        assertEquals(java.time.Instant.parse("2026-10-05T12:34:56Z").toEpochMilli(), com.ridetrack.app.studio.PhoneVideos.parseDate("20261005T123456.000Z"))
        assertEquals(0, com.ridetrack.app.studio.PhoneVideos.parseDate("19040101T000000.000Z"))
        assertEquals(0, com.ridetrack.app.studio.PhoneVideos.parseDate(null))
        assertEquals(0, com.ridetrack.app.studio.PhoneVideos.parseDate("garbage"))
    }
}
