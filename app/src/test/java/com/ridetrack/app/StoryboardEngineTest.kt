package com.ridetrack.app

import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.storyboard.ChatMessage
import com.ridetrack.app.studio.storyboard.Gear
import com.ridetrack.app.studio.storyboard.GearItem
import com.ridetrack.app.studio.storyboard.ShotFields
import com.ridetrack.app.studio.storyboard.StoryboardChat
import com.ridetrack.app.studio.storyboard.StoryboardEngine
import com.ridetrack.app.studio.storyboard.StoryboardStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardEngineTest {
    /** The tunnel example, as Gemini would answer the first message. */
    private val first = """
        Sure! Here's a first draft.
        {"reply":"Here's a first draft: 4 shots. I called step one \"The Look\".",
         "script":{"title":"How to enter a tunnel 101","format":"reel","musicNote":"dark build, the drop on the entry",
          "sections":[
           {"kind":"hook","text":"How to enter a tunnel 101","shots":[{"id":"s1","action":"The tunnel mouth from the road","framing":"wide","camera":"phone back","mount":"tripod at the roadside","riding":false,"sec":2,"audio":"music","match":{"events":[],"words":["tunnel"],"minSec":1,"engine":false,"mustRecord":false}}]},
           {"kind":"setup","text":"STEP 1: THE LOOK","shots":[{"id":"s2","action":"Your eyes through the visor lock on the tunnel","framing":"extreme close-up","camera":"phone front","mount":"handlebar","riding":true,"sec":1.5,"fallback":{"ideal":"helmet chin cam","reason":"eyes need to be close","using":"phone on the handlebar mount, front camera"},"match":{"mustRecord":true}}]},
           {"kind":"peak","text":null,"shots":[{"id":"s3","action":"Full throttle into the dark","framing":"medium","camera":"phone back","mount":"handlebar","sec":0.2,"audio":"engine","match":{"events":["strong acceleration"],"engine":true}},
                                               {"id":"s3","action":"Inside the tunnel, lights streaking past","sec":60,"audio":"engine"}]}],
          "texts":[{"text":"STEP 2","shot":"s3","sec":2}]},
         "patches":[],"memory":[{"op":"add","kind":"extra","name":"tripod","detail":"","confirm":"Added: tripod"}],
         "needsClarification":{"question":"Does the DJI Mic have both transmitters?","choices":["Yes, both","Just one"]},"unlock":[],"newVersion":null}
    """.trimIndent()

    @Test fun `a first answer becomes a checked storyboard script`() {
        val r = StoryboardEngine.parse(first, null)!!
        val s = r.script!!
        assertEquals("How to enter a tunnel 101", s.title)
        assertEquals(PieceFormat.REEL, s.format)
        assertEquals(listOf(SectionKind.HOOK, SectionKind.SETUP, SectionKind.PEAK), s.sections.map { it.kind })
        val shots = s.sections.flatMap { it.shots }
        // Ids are unique (the repeated "s3" got a new one); lengths kept to 0.5–10 s.
        assertEquals(shots.size, shots.mapNotNull { it.id }.toSet().size)
        assertEquals(500L, shots[2].durMs)
        assertEquals(10_000L, shots[3].durMs)
        assertEquals("helmet chin cam", shots[1].board!!.fallback!!.ideal)
        assertTrue(shots[1].board!!.match.mustRecord)
        assertEquals(false, shots[0].board!!.riding)
        assertEquals("dark build, the drop on the entry", s.musicNote)
        assertEquals("Does the DJI Mic have both transmitters?", r.question)
        assertEquals(listOf("Yes, both", "Just one"), r.choices)
        assertEquals(1, r.memory.size)
    }

    @Test fun `patches change only what they name, never a locked field, unless unlocked`() {
        val s0 = StoryboardEngine.parse(first, null)!!.script!!
        val locked = s0.copy(sections = s0.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == "s2") it.copy(locked = setOf(ShotFields.ACTION)) else it }) })
        val answer = """{"reply":"Done","patches":[
            {"op":"update","id":"s2","fields":{"action":"Eyes wide open","sec":2.5}},
            {"op":"update","id":"nope","fields":{"action":"x"}},
            {"op":"insert","after":"s1","section":"hook","shot":{"id":"s9","action":"Visor snaps down","sec":1}},
            {"op":"delete","id":"s1"}]}"""
        val r = StoryboardEngine.parse(answer, locked)!!
        assertEquals(3, r.patches.size) // the patch to a shot that doesn't exist is refused
        val s = StoryboardEngine.apply(locked, r.patches, r.unlock)
        val s2 = s.sections.flatMap { it.shots }.first { it.id == "s2" }
        assertEquals("Your eyes through the visor lock on the tunnel", s2.board!!.action)
        assertEquals(2_500L, s2.durMs)
        assertTrue(s.sections.flatMap { it.shots }.none { it.id == "s1" })
        assertEquals("Visor snaps down", s.sections[0].shots.first().board!!.action)
        val unlocked = StoryboardEngine.apply(locked, r.patches.take(1), setOf("s2.action"))
        assertEquals("Eyes wide open", unlocked.sections.flatMap { it.shots }.first { it.id == "s2" }.board!!.action)
    }

    @Test fun `a too-long script is shortened, sparing locked lengths`() {
        val long = first.replace("\"sec\":60", "\"sec\":10").replace("\"format\":\"reel\"", "\"format\":\"story\"")
        val s = StoryboardEngine.parse(long, null)!!.script!!
        assertTrue(s.totalMs <= PieceFormat.STORY.maxSec * 1000L)
    }

    @Test fun `what isn't JSON, or has no reply, is refused (the caller retries once)`() {
        assertNull(StoryboardEngine.parse("Sorry, I can't help with that.", null))
        assertNull(StoryboardEngine.parse("""{"script":null}""", null))
        assertNull(StoryboardEngine.parse("""{"reply":"x","script":{"title":"t","sections":[]}}""", null))
    }

    @Test fun `gear changes come with chips that undo them`() {
        val g0 = Gear(listOf(GearItem("CAMERA", "phone"), GearItem("EXTRA", "selfie stick")))
        val r = StoryboardEngine.parse("""{"reply":"ok","memory":[{"op":"add","kind":"camera","name":"GoPro","confirm":"Added: GoPro"},{"op":"remove","kind":"extra","name":"Selfie stick","confirm":"Removed: selfie stick"},{"op":"add","kind":"helper","name":"a friend","confirm":"A friend can film"}]}""", null)!!
        val (g, chips) = StoryboardEngine.remember(g0, r.memory)
        assertEquals(listOf("phone", "GoPro"), g.items.map { it.name })
        assertTrue(g.helper && g.asked)
        assertEquals(3, chips.size)
        val back = StoryboardEngine.undo(StoryboardEngine.undo(g, chips[0]), chips[1])
        assertEquals(setOf("phone", "selfie stick"), back.items.map { it.name }.toSet())
    }

    @Test fun `the prompt carries the gear, the script with its locks, and the chat`() {
        val s = StoryboardEngine.parse(first, null)!!.script!!.let { sc -> sc.copy(sections = sc.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == "s2") it.copy(locked = setOf("action")) else it }) }) }
        val chat = StoryboardChat("c", "t", 0, 0, listOf(ChatMessage(true, "make it faster", 1)), listOf(s))
        val p = StoryboardEngine.prompt(chat, Gear(listOf(GearItem("EXTRA", "tripod"))), null, "now a long version", firstChat = false)
        assertTrue("tripod" in p && "LOCKED: action" in p && "Rider: make it faster" in p && "RIDER NOW: now a long version" in p)
        assertTrue("never handheld" in p)
    }

    @Test fun `chats and gear survive a restart`() {
        val s = StoryboardEngine.parse(first, null)!!.script!!
        val chat = StoryboardChat("c1", "Tunnel", 1, 2, listOf(ChatMessage(true, "hi", 1, voiceMs = 2_400), ChatMessage(false, "draft", 2, version = 1, question = "Q?", choices = listOf("a"))), listOf(s), listOf("r1"), listOf("{}"))
        val json = org.json.JSONObject(StoryboardStore.write(listOf(chat), Gear(listOf(GearItem("MOUNT", "handlebar", "facing back")), helper = true, asked = true), "2026-10", 2))
        val back = StoryboardStore.readChat(json.getJSONArray("chats").getJSONObject(0))
        assertNotNull(back)
        assertEquals(chat, back)
        assertEquals(Gear(listOf(GearItem("MOUNT", "handlebar", "facing back")), helper = true, asked = true), StoryboardStore.readGear(json.getJSONObject("gear")))
    }

    @Test fun `a chat is named from its first message`() {
        assertEquals("How to enter a tunnel 101", StoryboardEngine.titleOf("Title is how to enter a tunnel 101. Step one: the ___"))
        assertEquals("Sunday 5 am, alarm goes off", StoryboardEngine.titleOf("Sunday 5 am, alarm goes off. It's raining"))
    }
}
