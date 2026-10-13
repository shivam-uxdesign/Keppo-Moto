package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.Footage
import com.ridetrack.app.studio.JumpCuts
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.ScriptWriter
import com.ridetrack.app.studio.SecondHook
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.SoundOn
import com.ridetrack.app.studio.StickerKind
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioText
import com.ridetrack.app.studio.TrackKind
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetterVideosTest {
    @Test
    fun `jump cuts take out long pauses, umms and false starts, not the riding around them`() {
        val lines = listOf(
            CaptionLine(1_000, 2_000, "Bhai aaj"),
            CaptionLine(2_100, 3_500, "Bhai aaj traffic bahut hai"), // started again
            CaptionLine(5_000, 5_400, "umm"),
            CaptionLine(6_500, 8_000, "aur garmi bhi"),
        )
        val r = JumpCuts.ranges(0, 10_000, lines)
        // Riding before the first line stays; the false start's own words go with the pause after it
        // only when long: here "Bhai aaj" → "Bhai aaj traffic…" is 100 ms, so they stay one piece.
        assertEquals(2, r.size)
        assertEquals(0L, r.first().first)
        assertEquals(3_700L, r.first().last) // a breath after "…bahut hai"
        assertEquals(6_350L, r.last().first) // just before "aur garmi bhi"
        assertEquals(10_000L, r.last().last)
        // Nothing to cut: one range.
        assertEquals(listOf(0L..10_000L), JumpCuts.ranges(0, 10_000, listOf(CaptionLine(1_000, 2_000, "ek"), CaptionLine(2_300, 3_000, "do"))))
        assertTrue(JumpCuts.isFiller("Umm…") && JumpCuts.isFiller("hmm") && !JumpCuts.isFiller("Aaaahh"))
        assertTrue(JumpCuts.falseStart("Ye road", "Ye road toh mast hai"))
    }

    @Test
    fun `a talking shot is cut into pieces in the plan, unless jump cuts are off`() {
        val f = Footage("c1", "m1", 12_000, 0, "selfie", listOf(CaptionLine(500, 2_500, "Paani nahi piya"), CaptionLine(6_000, 8_000, "teen ghante se")), 40)
        val sc = Script(PieceFormat.SHORT, "Paani", null, 12, "one_take", null, null, listOf(Section(SectionKind.PEAK, "take", listOf(ScriptShot("m1", 0, 9_000)))))
        val on = ScriptWriter.toPlan(sc, listOf(f), emptyList(), Vibe.VLOG, StudioOptions(outro = false, loopEnd = false)).plan
        assertEquals(2, on.segments.filterIsInstance<ClipSegment>().size)
        val off = ScriptWriter.toPlan(sc, listOf(f), emptyList(), Vibe.VLOG, StudioOptions(outro = false, loopEnd = false, jumpCuts = false)).plan
        assertEquals(1, off.segments.filterIsInstance<ClipSegment>().size)
    }

    @Test
    fun `sound on pieces come from the fastest quiet stretches, engine only, speed on`() {
        val fs = listOf(
            Footage("c1", "m1", 30_000, 0, "road", listOf(CaptionLine(1_000, 3_000, "dekho")), 92),
            Footage("c2", "m2", 6_000, 0, "road", emptyList(), 110), // too short
            Footage("c3", "m3", 20_000, 0, "road", emptyList(), 20), // too slow
        )
        val s = SoundOn.scripts(fs)
        assertEquals(listOf("Sound on · 92 km/h"), s.map { it.title })
        val shot = s.single().sections.single().shots.single()
        assertTrue(shot.inMs >= 3_300 && shot.durMs in 8_000..15_000, "$shot")
        val plan = ScriptWriter.toPlan(s.single(), fs, emptyList(), Vibe.HYPE, StudioOptions(outro = false, loopEnd = false, jumpCuts = false)).plan
        val done = SoundOn.apply(plan, mapOf("m1" to "file:///engine.m4a")) { "x" }
        assertEquals(0f, done.segments.filterIsInstance<ClipSegment>().single().volume)
        assertEquals(1, done.audio.count { it.kind == TrackKind.ENGINE })
        assertTrue(done.stickers.any { it.kind == StickerKind.SPEED })
        assertTrue(!done.mix.duck)
    }

    @Test
    fun `a second hook opens on the peak and sits under the idea`() {
        val sc = Script(
            PieceFormat.REEL, "Chain", null, 30, "story", "Phir se?", null,
            listOf(
                Section(SectionKind.HOOK, "sound", listOf(ScriptShot("a", 0, 2_000))),
                Section(SectionKind.SETUP, "talk", listOf(ScriptShot("b", 0, 6_000))),
                Section(SectionKind.PEAK, "talk", listOf(ScriptShot("c", 1_000, 9_000))),
            ),
        )
        val h = assertNotNull(SecondHook.of(sc))
        assertEquals("Chain", h.variationOf)
        assertEquals(ScriptShot("c", 1_000, 3_500), h.sections.first().shots.single())
        assertEquals(listOf(SectionKind.HOOK, SectionKind.SETUP, SectionKind.PEAK), h.sections.map { it.kind })
        assertNull(SecondHook.of(sc.copy(format = PieceFormat.CAPTION)))
        assertNull(SecondHook.of(h))
    }

    @Test
    fun `english subtitles are read, kept and asked for`() {
        val lines = StudioText.parseLines("""{"lines":[{"start":1,"end":2,"text":"bhai ye road mast hai","en":"bro this road is great"},{"start":3,"end":4,"text":"Let's go","en":"Let's go"}]}""")!!
        assertEquals("bro this road is great", lines[0].en)
        assertNull(lines[1].en) // English already
        assertTrue(StudioText.CAPTIONS_PROMPT.contains("\"en\""))
        assertTrue(StudioText.batchPrompt(3).contains("\"en\""))
        assertEquals(listOf("hello bro", null), StudioText.parseEnglish("""{"en":["hello bro",""]}""", 2))
        assertTrue(StudioText.englishPrompt(listOf("bhai")).contains("exactly 1 entries"))
        val o = ReelJson.readOptionsJson(ReelJson.writeOptionsJson(StudioOptions(english = true, jumpCuts = false)))
        assertTrue(o.english && !o.jumpCuts)
    }
}
