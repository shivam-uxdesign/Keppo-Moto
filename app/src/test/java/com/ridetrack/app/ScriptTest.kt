package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.Footage
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.ScriptWriter
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.Vibe
import com.ridetrack.app.studio.describe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptTest {
    /** The 7 Oct Reel's ride: talking selfie clips, one "Tooooo", one road clip. */
    private val footage = listOf(
        Footage("c1", "m1", 20_000, 1_000_000, "selfie", listOf(CaptionLine(6_000, 8_000, "Arre yaar, ye toh thoda"), CaptionLine(8_200, 9_400, "garmi hai")), 54),
        Footage("c2", "m2", 15_000, 1_060_000, "selfie", listOf(CaptionLine(2_000, 6_500, "Paani nahi piya teen ghante se"), CaptionLine(6_800, 9_000, "ek detailed report banaunga")), 48),
        Footage("c3", "m3", 12_000, 1_120_000, "selfie", listOf(CaptionLine(4_000, 7_600, "Tooooooo")), 52),
        Footage("c4", "m4", 30_000, 1_180_000, "road", emptyList(), 60),
        Footage("c5", "m5", 18_000, 1_240_000, "selfie", emptyList(), 40),
    )

    private val reply = """
        {"pieces":[{"format":"reel","title":"No water, 3 hours","why":"one idea","lengthSec":30,"shape":"story","hookLine":"3 hours. No water.",
         "caption":"Paani? #motovlog","sections":[
          {"kind":"hook","form":"sound","text":"","shots":[{"clip":"c3","in":4.1,"out":7.5}]},
          {"kind":"peak","form":"take","text":"","shots":[{"clip":"c2","in":3.0,"out":8.0}]},
          {"kind":"build","form":"montage","text":"","shots":[{"clip":"c5","in":1,"out":2},{"clip":"c5","in":2.2,"out":3},{"clip":"c9","in":0,"out":2}]},
          {"kind":"ending","form":"stats","shots":[]}]},
         {"format":"short","title":"Tooooo","lengthSec":8,"sections":[{"kind":"hook","form":"sound","shots":[{"clip":"c3","in":3.8,"out":7.8}]}]}]}
    """.trimIndent()

    @Test
    fun `changed captions show in the plan, a blank one is hidden, and both are saved`() {
        val script = ScriptWriter.parsePieces(reply, footage)[0].copy(
            story = "No water for three hours.",
            captions = mapOf(ScriptWriter.captionKey("m2", 2_000) to "No water for 3 hours", ScriptWriter.captionKey("m2", 6_800) to ""),
        )
        val planned = ScriptWriter.toPlan(script, footage, emptyList(), Vibe.HYPE, StudioOptions(outro = false, loopEnd = false))
        val peak = planned.plan.segments.filterIsInstance<ClipSegment>().first { it.bit.momentId == "m2" }
        assertEquals(listOf("No water for 3 hours"), peak.lines.map { it.text })
        val back = com.ridetrack.app.studio.ScriptJson.read(com.ridetrack.app.studio.ScriptJson.write(script))!!
        assertEquals(script.captions, back.captions)
        assertEquals("No water for three hours.", back.story)
    }

    @Test
    fun `a script without a written story gets one from what was said`() {
        val script = ScriptWriter.parsePieces(reply, footage)[0]
        val story = ScriptWriter.storyOf(script, footage)
        assertTrue(story.startsWith("${SectionKind.HOOK.label}: \u201cTooooooo\u201d"), story)
    }

    @Test
    fun `reaction sounds are spotted`() {
        assertTrue(ScriptWriter.isSound("Tooooooo"))
        assertTrue(ScriptWriter.isSound("Aaaahh!"))
        assertTrue(ScriptWriter.isSound("Fhit!"))
        assertTrue(!ScriptWriter.isSound("Paani nahi piya teen ghante se"))
    }

    @Test
    fun `Gemini's pieces are read with clip keys mapped to moments`() {
        val pieces = ScriptWriter.parsePieces(reply, footage)
        assertEquals(2, pieces.size)
        assertEquals(PieceFormat.SHORT, pieces[1].format)
        val p = pieces[0]
        assertEquals("m3", p.sections[0].shots[0].clip)
        assertEquals(SectionKind.HOOK, p.sections[0].kind)
        // The unknown clip c9 is dropped.
        assertEquals(2, p.sections[2].shots.size)
    }

    @Test
    fun `the plan keeps sentences whole, merges look-alike quick shots and fits the length`() {
        val script = ScriptWriter.parsePieces(reply, footage)[0]
        val planned = ScriptWriter.toPlan(script, footage, emptyList(), Vibe.HYPE, StudioOptions(outro = true, loopEnd = true))
        val clips = planned.plan.segments.filterIsInstance<ClipSegment>().filter { !it.tail }
        // Peak asked for 3.0–8.0 s, cutting both sentences: widened to 1.85–9.25 s.
        val peak = clips[1]
        assertEquals(1_850, peak.inMs)
        assertEquals(7_400, peak.durMs)
        // The two 1 s silent shots of c5 became one longer shot.
        val build = clips.filter { it.bit.momentId == "m5" }
        assertEquals(1, build.size)
        assertTrue(build[0].durMs >= 2_000)
        assertTrue(planned.plan.segments.any { it is StatsSegment })
        // The script ends on stats, so no loop back.
        assertTrue(planned.plan.segments.none { it is ClipSegment && it.tail })
        assertTrue(planned.plan.totalMs <= 31_000)
        // One section, one transition: the peak and build are different sections.
        assertEquals(listOf(0, 1, 2), clips.map { it.section })
    }

    @Test
    fun `a script longer than its length is cut to fit, build first`() {
        val script = ScriptWriter.parsePieces(reply, footage)[0].copy(lengthSec = 12)
        val planned = ScriptWriter.toPlan(script, footage, emptyList(), Vibe.HYPE, StudioOptions(outro = false, loopEnd = false))
        assertTrue(planned.plan.totalMs <= 13_000, "total ${planned.plan.totalMs}")
        assertTrue(planned.plan.clips.none { it.bit.momentId == "m5" })
        assertTrue(planned.plan.clips.first().hook)
    }

    @Test
    fun `without Gemini the app writes a calm script with few long shots`() {
        val s = ScriptWriter.local(footage, PieceFormat.REEL, 30, "Tuesday Evening Ride")!!
        assertEquals(SectionKind.HOOK, s.sections.first().kind)
        assertEquals("sound", s.sections.first().form)
        assertEquals("m3", s.sections.first().shots.first().clip)
        val planned = ScriptWriter.toPlan(s, footage, emptyList(), Vibe.HYPE, StudioOptions())
        val clips = planned.plan.clips
        assertTrue(clips.size in 3..6, "${clips.size} shots")
        assertTrue(planned.plan.totalMs <= 31_000)
        assertTrue(clips.map { it.durMs }.average() >= 2_500)
    }

    @Test
    fun `the content summary says how much there is`() {
        val s = ScriptWriter.summary(footage)
        assertEquals(5, s.clips)
        assertEquals(1, s.sounds)
        assertTrue(s.text.contains("of you talking"))
    }

    @Test
    fun `script edits resize inside the clip, move, remove, swap, pick a hook`() {
        val sc = ScriptWriter.parsePieces(reply, footage)[0]
        val longer = com.ridetrack.app.studio.ScriptEdits.resize(sc, 1, 100_000, footage)
        assertEquals(15_000, longer.sections[1].shots.last().outMs)
        val moved = com.ridetrack.app.studio.ScriptEdits.move(sc, 1, -1)
        assertEquals(SectionKind.PEAK, moved.sections[0].kind)
        assertEquals(3, com.ridetrack.app.studio.ScriptEdits.remove(sc, 2).sections.size)
        val swapped = com.ridetrack.app.studio.ScriptEdits.swap(sc, 1, footage[0])
        assertEquals("m1", swapped.sections[1].shots.single().clip)
        assertEquals(5_850, swapped.sections[1].shots.single().inMs)
        val choices = com.ridetrack.app.studio.ScriptEdits.hookChoices(footage)
        assertEquals("Tooooooo", choices.first().second)
        val hooked = com.ridetrack.app.studio.ScriptEdits.hook(moved, choices[1].first, false)
        assertEquals(SectionKind.HOOK, hooked.sections.first().kind)
        assertEquals(1, hooked.sections.count { it.kind == SectionKind.HOOK })
    }

    @Test
    fun `a script reads as one line for the style log`() {
        val line = ScriptWriter.parsePieces(reply, footage)[0].describe(footage)
        assertTrue(line.startsWith("Reel, story: Hook (sound, 3.4 s)"), line)
        assertTrue(line.contains("Tooooooo"))
        assertTrue(line.contains("Ending (stats)"))
    }

    @Test
    fun `a piece that comes out too short is filled from its own clips, and look-alike shots are kept`() {
        // Four selfie clips, each 20 s with one line; the script uses 2-3 s of each: about 11 s for a 30 s piece.
        val fs = (1..4).map { k ->
            Footage("c$k", "s$k", 20_000, 1_000_000L + k * 60_000, "selfie", listOf(CaptionLine(1_000, 2_500, "line $k")), 40)
        }
        val shots = fs.mapIndexed { i, f -> com.ridetrack.app.studio.Section(if (i == 0) SectionKind.HOOK else SectionKind.PEAK, "line", listOf(com.ridetrack.app.studio.ScriptShot(f.momentId, 800, 3_500))) }
        val script = com.ridetrack.app.studio.Script(PieceFormat.REEL, "Short one", null, 30, "story", null, null, shots)
        val planned = ScriptWriter.toPlan(script, fs, emptyList(), Vibe.VLOG, StudioOptions(outro = false, loopEnd = false))
        // All four shots stay (none dropped for looking alike)...
        assertEquals(4, planned.plan.clips.size)
        // ...and the piece reaches at least 70% of its 30 s.
        assertTrue(planned.plan.totalMs >= 21_000, "total ${planned.plan.totalMs}")
        assertEquals(30_000, planned.plannedMs)
        assertTrue(planned.fixes.any { it.startsWith("filled") })
    }
}
