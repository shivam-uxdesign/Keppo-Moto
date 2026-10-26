package com.ridetrack.app

import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ScriptJson
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.storyboard.Framing
import com.ridetrack.app.studio.storyboard.GearFallback
import com.ridetrack.app.studio.storyboard.MatchHints
import com.ridetrack.app.studio.storyboard.OnScreenText
import com.ridetrack.app.studio.storyboard.Placeholders
import com.ridetrack.app.studio.storyboard.Storyboard
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardModelTest {
    private val eyes = Storyboard(
        action = "Your eyes through the visor narrow and lock on the tunnel", framing = "extreme close-up", camera = "phone front", mount = "handlebar",
        fallback = GearFallback("helmet chin cam", "the eyes need to be close", "phone on the handlebar mount, front camera tilted up"),
        durationMs = 1_500, say = null, audio = "voice", match = MatchHints(events = listOf("voice"), mustRecord = true),
    )

    @Test fun `a script written before storyboards still reads the same`() {
        val old = """{"format":"REEL","title":"Sunday","lengthSec":20,"shape":"story","sections":[{"kind":"HOOK","form":"sound","shots":[{"clip":"m1","in":1000,"out":3000}]}]}"""
        val s = ScriptJson.read(JSONObject(old))!!
        assertEquals(listOf(ScriptShot("m1", 1000, 3000)), s.sections[0].shots)
        assertTrue(s.texts.isEmpty())
        assertNull(s.musicNote)
        assertEquals(listOf("m1"), s.clips)
        // Written back without any storyboard fields.
        val again = ScriptJson.write(s).toString()
        assertTrue("board" !in again && "texts" !in again && "musicNote" !in again)
    }

    @Test fun `storyboard shots round-trip with their locks, texts and music note`() {
        val s = Placeholders.empty(
            "How to enter a tunnel 101", PieceFormat.REEL,
            listOf(SectionKind.HOOK to listOf("s1" to eyes.copy(framing = "wide")), SectionKind.SETUP to listOf("s2" to eyes)),
            texts = listOf(OnScreenText("STEP 1: THE LOOK", "s2", 2_000)), musicNote = "dark build, drop on the entry",
        ).let { it.copy(sections = it.sections.map { sec -> sec.copy(shots = sec.shots.map { sh -> if (sh.id == "s2") sh.copy(locked = setOf("action")) else sh }) }) }
        val back = ScriptJson.read(ScriptJson.write(s))!!
        assertEquals(s.sections, back.sections)
        assertEquals(s.texts, back.texts)
        assertEquals(s.musicNote, back.musicNote)
        assertTrue(back.clips.isEmpty())
        assertTrue(back.sections[1].shots[0].placeholder)
        assertEquals(3_000L, back.totalMs)
    }

    @Test fun `numbers, fresh ids and framing words`() {
        val s = Placeholders.empty("t", PieceFormat.REEL, listOf(SectionKind.HOOK to listOf("s1" to eyes, "s2" to eyes)))
        assertEquals("02", Placeholders.number(s, "s2"))
        assertEquals("s3", Placeholders.newId(s))
        assertEquals(Framing.EXTREME, Framing.of("Extreme close-up"))
        assertEquals(Framing.CLOSE, Framing.of("close-up"))
        assertEquals(Framing.WIDE, Framing.of("wide establishing"))
        assertEquals(Framing.MEDIUM, Framing.of("over the shoulder"))
    }

    @Test fun `a template Reel keeps what it was made from`() {
        val p = com.ridetrack.app.studio.ReelProject(
            id = "r1", rideId = null, createdAt = 1, updatedAt = 2, title = "Tunnel", series = "", episode = 0, hookLine = "", postCaption = "", story = null,
            options = com.ridetrack.app.studio.StudioOptions(), musicUri = null, musicName = null,
            plan = com.ridetrack.app.studio.StudioPlan(emptyList(), com.ridetrack.app.studio.Vibe.HYPE), takes = emptyList(), tips = emptyList(), durationMs = 0,
            template = com.ridetrack.app.studio.TemplateInfo("chat1", 2, 11, setOf("s6")),
        )
        assertEquals(p.template, ReelJson.read(ReelJson.write(p))?.template)
        assertNull(ReelJson.read(ReelJson.write(p.copy(template = null)))?.template)
    }

    @Test fun `a template keeps its footage and caption choices, older ones read as before`() {
        val t = com.ridetrack.app.studio.TemplateInfo("chat1", 1, 3, clips = com.ridetrack.app.studio.storyboard.ClipSource.FRESH, captions = com.ridetrack.app.studio.storyboard.CaptionSource.SAID)
        val p = com.ridetrack.app.studio.ReelProject(
            id = "r1", rideId = null, createdAt = 1, updatedAt = 2, title = "Chai", series = "", episode = 0, hookLine = "", postCaption = "", story = null,
            options = com.ridetrack.app.studio.StudioOptions(), musicUri = null, musicName = null,
            plan = com.ridetrack.app.studio.StudioPlan(emptyList(), com.ridetrack.app.studio.Vibe.HYPE), takes = emptyList(), tips = emptyList(), durationMs = 0,
            template = t,
        )
        assertEquals(t, ReelJson.read(ReelJson.write(p))?.template)
        val old = JSONObject(ReelJson.write(p)).apply { getJSONObject("template").remove("clips"); getJSONObject("template").remove("captions") }
        val read = ReelJson.read(old.toString())!!.template!!
        assertEquals(com.ridetrack.app.studio.storyboard.ClipSource.AUTO, read.clips)
        assertEquals(com.ridetrack.app.studio.storyboard.CaptionSource.WRITTEN, read.captions)
    }

    @Test fun `fresh footage only allows clips filmed after the template was made`() {
        val fresh = com.ridetrack.app.studio.storyboard.ClipSource.FRESH
        assertTrue(fresh.allows(clipMillis = 2_000, madeMillis = 1_000))
        assertTrue(!fresh.allows(clipMillis = 500, madeMillis = 1_000))
        assertTrue(com.ridetrack.app.studio.storyboard.ClipSource.SUGGEST.allows(500, 1_000))
        assertTrue(!com.ridetrack.app.studio.storyboard.ClipSource.SUGGEST.fillsByItself)
    }

    @Test fun `written captions keep the words exactly, in short phrases timed across the take`() {
        val lines = com.ridetrack.app.studio.storyboard.VoiceLines.written("Bhai, chai pe chalte hain. Lonavala ki baarish aur garam pakode!", 6_000)
        assertEquals("Bhai, chai pe chalte hain. Lonavala ki baarish aur garam pakode!", lines.joinToString(" ") { it.text })
        assertEquals("Bhai,", lines.first().text)
        assertEquals(0L, lines.first().startMs)
        assertTrue(lines.last().endMs in 5_990L..6_000L)
        assertTrue(lines.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 })
        assertTrue(lines.all { it.text.split(' ').size <= 6 })
        assertTrue(com.ridetrack.app.studio.storyboard.VoiceLines.written("  ", 3_000).isEmpty())
    }
}
