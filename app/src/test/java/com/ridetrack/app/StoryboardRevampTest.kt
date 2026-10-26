package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.storyboard.Animatic
import com.ridetrack.app.studio.storyboard.Captions
import com.ridetrack.app.studio.storyboard.ChatMessage
import com.ridetrack.app.studio.storyboard.Framing
import com.ridetrack.app.studio.storyboard.FramingGuide
import com.ridetrack.app.studio.storyboard.Gear
import com.ridetrack.app.studio.storyboard.GearItem
import com.ridetrack.app.studio.storyboard.Health
import com.ridetrack.app.studio.storyboard.OnScreenText
import com.ridetrack.app.studio.storyboard.Placeholders
import com.ridetrack.app.studio.storyboard.RideBrief
import com.ridetrack.app.studio.storyboard.ShotEdits
import com.ridetrack.app.studio.storyboard.ShotFields
import com.ridetrack.app.studio.storyboard.Storyboard
import com.ridetrack.app.studio.storyboard.StoryboardChat
import com.ridetrack.app.studio.storyboard.StoryboardEngine
import com.ridetrack.app.studio.storyboard.StoryboardJson
import com.ridetrack.app.studio.storyboard.StoryboardStore
import com.ridetrack.app.studio.storyboard.VoiceLines
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardRevampTest {
    private fun board(action: String, say: String? = null, ms: Long = 2_000, riding: Boolean = false, mount: String = "tripod") =
        Storyboard(action, say = say, durationMs = ms, riding = riding, mount = mount)

    private val chai = Placeholders.empty(
        "Chai stop", PieceFormat.REEL,
        listOf(
            SectionKind.HOOK to listOf("s1" to board("Kettle steaming", "Bhai, ye chai ke liye 60 km aaya hoon.")),
            SectionKind.PEAK to listOf("s2" to board("Riding into the ghat", "Baarish aur ghat.", 3_000, riding = true, mount = "handlebar"), "s3" to board("The pour", null, 2_500)),
        ),
        texts = listOf(OnScreenText("The pour", "s3", 2_000)),
    )

    @Test fun `a change to selected shots leaves every other shot exactly as it was`() {
        // Gemini rewrote everything, though only #2 was selected, and added a shot after it.
        val rewrite = chai.copy(sections = chai.sections.map { sec ->
            sec.copy(shots = sec.shots.flatMap { sh ->
                val b = sh.board!!.copy(say = "CHANGED")
                if (sh.id == "s2") listOf(sh.copy(board = b), sh.copy(id = "s9", board = board("Mist on the visor"))) else listOf(sh.copy(board = b))
            })
        })
        val out = StoryboardEngine.scoped(chai, rewrite, listOf("s2"))
        val shots = Placeholders.shots(out).map { it.second }
        assertEquals(listOf("s1", "s2", "s9", "s3"), shots.map { it.id })
        assertEquals("Bhai, ye chai ke liye 60 km aaya hoon.", shots[0].board!!.say)
        assertEquals("CHANGED", shots[1].board!!.say)
        assertEquals(null, shots[3].board!!.say)
        assertEquals(setOf("s2", "s9"), StoryboardEngine.changedIds(chai, out))
    }

    @Test fun `a removed selected shot goes, an unselected one never does`() {
        val without = chai.copy(sections = chai.sections.map { sec -> sec.copy(shots = sec.shots.filter { it.id == "s2" }) }.filter { it.shots.isNotEmpty() })
        val out = StoryboardEngine.scoped(chai, without, listOf("s3"))
        assertEquals(listOf("s1", "s2"), Placeholders.shots(out).map { it.second.id })
    }

    @Test fun `the rider's own script is checked word for word, stage directions aside`() {
        val script = "Bhai, ye chai ke liye 60 km aaya hoon.\nSHOT: kettle close-up\nTEXT: The pour\nBaarish aur ghat!"
        assertEquals(listOf("Bhai, ye chai ke liye 60 km aaya hoon.", "The pour", "Baarish aur ghat!"), StoryboardEngine.scriptLines(script))
        assertTrue(StoryboardEngine.missingLines(script, chai).isEmpty())
        val changed = ShotEdits.edit(chai, "s2") { it.copy(say = "Rain and the ghat.") }
        assertEquals(listOf("Baarish aur ghat!"), StoryboardEngine.missingLines(script, changed))
    }

    @Test fun `the rider's own edits go into one version, Keppo's each make their own`() {
        var ch = StoryboardChat("c", "t", 0, 0, listOf(ChatMessage(true, "idea", 1), ChatMessage(false, "draft", 2, version = 1)), listOf(chai))
        ch = ch.edited(ShotEdits.edit(chai, "s1") { it.copy(action = "a") })
        ch = ch.edited(ShotEdits.edit(ch.script!!, "s1") { it.copy(action = "b") })
        assertEquals(2, ch.versions.size)
        assertEquals("b", Placeholders.find(ch.script!!, "s1")!!.board!!.action)
        assertEquals("First draft", ch.versionName(0))
        assertEquals("Your edits", ch.versionName(1))
        val k = ch.withVersion(chai).copy(messages = ch.messages + ChatMessage(true, "make the hook punchier", 3) + ChatMessage(false, "done", 4, version = 3))
        assertEquals("make the hook punchier", k.versionName(2))
        assertEquals(4, k.edited(chai).versions.size)
    }

    @Test fun `pins, ride and captions survive a restart, and replies can pin rules`() {
        val withCc = ShotEdits.edit(chai, "s1") { it.copy(caption = Captions.CUSTOM, captionText = "60 km for chai") }
        val ch = StoryboardChat("c", "t", 0, 0, versions = listOf(withCc), pins = listOf("Under 30 s"), rideId = "r1")
        val back = StoryboardStore.readChat(StoryboardStore.writeChat(ch))!!
        assertEquals(ch, back)
        assertEquals("60 km for chai", Placeholders.find(back.script!!, "s1")!!.board!!.captionWords)
        val r = StoryboardEngine.parse("""{"reply":"Pinned it","script":null,"pin":["Under 30 s"],"intent":"rule"}""", chai)!!
        assertEquals(listOf("Under 30 s"), r.pins)
        assertEquals("rule", r.intent)
        assertNull(StoryboardEngine.parse("""{"reply":"x","intent":"idea|own_script|change"}""", null)!!.intent)
        val p = StoryboardEngine.prompt(ch, Gear(), null, "shorter", firstChat = false, scope = listOf("s1"), ride = "Ride “Friday”, 2 clips")
        assertTrue("RULES FOR THIS REEL" in p && "Under 30 s" in p && "ONLY THESE SHOTS: #1 (id s1)" in p && "Ride “Friday”" in p)
        assertTrue("captions \"60 km for chai\"" in p)
        val own = StoryboardEngine.prompt(ch, Gear(), null, "Line one", firstChat = false, ownScript = true)
        assertTrue("RIDER'S SCRIPT:\nLine one" in own && "RIDER NOW" !in own)
    }

    @Test fun `caption modes read from a shot`() {
        val b = StoryboardEngine.boardOf(JSONObject("""{"action":"x","say":"hi there","caption":"off"}"""))!!
        assertEquals(Captions.OFF, b.caption)
        assertNull(b.captionWords)
        assertEquals("hi there", b.copy(caption = Captions.SAY).captionWords)
        assertEquals(b.copy(caption = Captions.CUSTOM, captionText = "yo"), StoryboardJson.read(StoryboardJson.write(b.copy(caption = Captions.CUSTOM, captionText = "yo"))))
    }

    @Test fun `on-screen text and locks set by hand are the rider's`() {
        val s = ShotEdits.setText(chai, "s1", "60 km")
        assertEquals(listOf("60 km", "The pour"), s.texts.map { it.text })
        assertTrue(ShotFields.TEXT in Placeholders.find(s, "s1")!!.locked)
        // A rewrite keeps the rider's text.
        val merged = StoryboardEngine.merge(s, s.copy(texts = emptyList()))
        assertEquals(listOf("60 km"), merged.texts.map { it.text })
        assertTrue(ShotFields.SAY in Placeholders.find(ShotEdits.lock(chai, "s2", ShotFields.SAY, true), "s2")!!.locked)
        assertEquals(listOf("The pour"), ShotEdits.setText(s, "s1", "").texts.map { it.text })
    }

    @Test fun `health finds long lines, handheld riding, a missing tripod and silent captions, with fixes`() {
        val s = ShotEdits.edit(chai, "s2") { it.copy(mount = "handheld", caption = Captions.OFF) }
        val issues = Health.issues(s, Gear(listOf(GearItem("MOUNT", "handlebar"))))
        val text = issues.joinToString("\n") { it.text }
        assertTrue("line in #1" in text && "handheld" in text && "tripod" in text && "captions are off" in text, text)
        val long = issues.first { "line in #1" in it.text }
        val fixed = long.fixes.single().second(s)
        assertEquals(Health.halfUp(Health.sayMs("Bhai, ye chai ke liye 60 km aaya hoon.")), Placeholders.find(fixed, "s1")!!.board!!.durationMs)
        assertTrue(Health.issues(fixed, Gear()).none { "line in #1" in it.text })
        assertEquals(0L, Health.sayMs("  "))
    }

    @Test fun `the animatic shows each shot for its length with its captions and text`() {
        val f = Animatic.frames(chai)
        assertEquals(listOf(0L, 2_000L, 5_000L), f.map { it.startMs })
        assertEquals("s2", Animatic.at(f, 2_500)!!.shotId)
        assertEquals("Bhai,", Animatic.caption(f[0], 100))
        assertEquals("ye chai ke liye 60 km", Animatic.caption(f[0], 1_000))
        assertNull(Animatic.caption(f[2], 5_100))
        assertEquals("The pour", Animatic.text(chai, f[2], 5_100))
        assertNull(Animatic.text(chai, f[2], 7_200))
    }

    @Test fun `a shot's own picture carries captions only when no voice-over does`() {
        val said = listOf(CaptionLine(0, 900, "what I said"))
        assertEquals(emptyList(), VoiceLines.segmentLines(board("a", "line"), said, 2_000))
        assertEquals(said, VoiceLines.segmentLines(board("a"), said, 2_000))
        assertEquals(emptyList(), VoiceLines.segmentLines(board("a").copy(caption = Captions.OFF), said, 2_000))
        assertEquals("Custom words", VoiceLines.segmentLines(board("a").copy(caption = Captions.CUSTOM, captionText = "Custom words"), said, 2_000).joinToString(" ") { it.text })
    }

    @Test fun `framing guides and filming length`() {
        assertNull(FramingGuide.box(Framing.WIDE))
        assertTrue(FramingGuide.box(Framing.EXTREME)!!.second < FramingGuide.box(Framing.CLOSE)!!.second)
        assertTrue(FramingGuide.front(board("a").copy(camera = "phone front")))
        assertEquals(4_000L, FramingGuide.filmMs(board("a")))
    }

    @Test fun `a ride brief lists its clips for Keppo`() {
        val b = RideBrief.brief("Friday evening", listOf(RideBrief.Clip(52, listOf("voice", "engine rev"), listOf("chai"), "bhai chai", road = false)))
        assertEquals("Ride “Friday evening”, 1 clip: 1) 52 s selfie · voice, engine rev · chai · said “bhai chai”", b)
        assertTrue("no clips" in RideBrief.brief("x", emptyList()))
    }
}
