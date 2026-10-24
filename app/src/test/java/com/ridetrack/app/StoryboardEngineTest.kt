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

    @Test fun `asking to remove a shot by its number really removes it, and the card says so`() {
        val s0 = StoryboardEngine.parse(first, null)!!.script!!
        val ids = s0.sections.flatMap { it.shots }.map { it.id }
        // Gemini's whole updated script without shot #2 (and nothing else changed).
        val without = s0.copy(sections = s0.sections.map { sec -> sec.copy(shots = sec.shots.filter { it.id != ids[1] }) }.filter { it.shots.isNotEmpty() })
        val answer = org.json.JSONObject().put("reply", "Removed #2").put("changes", org.json.JSONArray(listOf("Removed #2")))
            .put("script", com.ridetrack.app.studio.ScriptJson.write(without).let { org.json.JSONObject(storyboardJson(without)) })
        val r = StoryboardEngine.parse(answer.toString(), s0)!!
        val next = StoryboardEngine.next(s0, r)!!
        assertTrue(next.sections.flatMap { it.shots }.none { it.id == ids[1] })
        val diff = StoryboardEngine.diff(s0, next)
        assertEquals("Removed #2: Your eyes through the visor lock on the tunnel", diff.single { it.startsWith("Removed") })
        assertEquals(listOf("Removed #2"), r.claimed)
    }

    @Test fun `an old-style delete patch works with "remove" and the shot number the rider sees`() {
        val s0 = StoryboardEngine.parse(first, null)!!.script!!
        val r = StoryboardEngine.parse("""{"reply":"ok","patches":[{"op":"remove","id":"3"}]}""", s0)!!
        assertEquals(1, r.patches.size)
        val next = StoryboardEngine.next(s0, r)!!
        assertEquals(3, next.sections.sumOf { it.shots.size })
        assertEquals(StoryboardEngine.resolveId(s0, "#3"), r.patches.single().id)
        assertEquals(null, StoryboardEngine.resolveId(s0, "shot 9"))
    }

    @Test fun `a full rewrite keeps the rider's hand edits and changes the rest`() {
        val s0 = StoryboardEngine.parse(first, null)!!.script!!
        val id = s0.sections.flatMap { it.shots }[1].id!!
        val mine = com.ridetrack.app.studio.storyboard.ShotEdits.edit(s0, id) { it.copy(action = "My own words") }
        val rewrite = mine.copy(sections = mine.sections.map { sec -> sec.copy(shots = sec.shots.map { sh -> sh.copy(board = sh.board!!.copy(action = "Gemini's words", durationMs = 3_000)) }) })
        val merged = StoryboardEngine.merge(mine, rewrite)
        val shot = merged.sections.flatMap { it.shots }.first { it.id == id }
        assertEquals("My own words", shot.board!!.action)
        assertEquals(3_000L, shot.board!!.durationMs)
        assertEquals(setOf(ShotFields.ACTION), shot.locked)
        assertEquals("Gemini's words", StoryboardEngine.merge(mine, rewrite, setOf("$id.action")).sections.flatMap { it.shots }.first { it.id == id }.board!!.action)
        assertTrue(StoryboardEngine.diff(mine, mine).isEmpty())
    }

    @Test fun `going back to a version doesn't copy it, and a new change starts from it`() {
        val s0 = StoryboardEngine.parse(first, null)!!.script!!
        val s1 = s0.copy(title = "Two")
        var chat = StoryboardChat("c", "t", 0, 0).withVersion(s0).withVersion(s1)
        assertEquals(1, chat.currentIndex)
        chat = chat.copy(current = 0)
        assertEquals(2, chat.versions.size)
        assertEquals(s0, chat.script)
        chat = chat.withVersion(s0.copy(title = "Three"))
        assertEquals(3, chat.versions.size)
        assertEquals("Three", chat.script!!.title)
        val back = StoryboardStore.readChat(org.json.JSONObject(StoryboardStore.write(listOf(chat.copy(current = 1, messages = listOf(ChatMessage(false, "x", 1, version = 2, changes = listOf("Title: Two"))))), Gear(), "2026-10", 1)).getJSONArray("chats").getJSONObject(0))!!
        assertEquals(1, back.currentIndex)
        assertEquals(listOf("Title: Two"), back.messages.single().changes)
    }

    @Test fun `the prompt asks for a shape that fits the idea, and questions before guessing`() {
        val p = StoryboardEngine.prompt(StoryboardChat("c", "t", 0, 0), Gear(), null, "a reel about my chai stop", firstChat = true)
        assertTrue("no fixed number of shots" in p && "ASK BEFORE GUESSING" in p && "ALWAYS return the whole updated" in p)
    }

    /** A script written back the way Gemini sends it. */
    private fun storyboardJson(s: com.ridetrack.app.studio.Script): String = org.json.JSONObject()
        .put("title", s.title).put("format", s.format.name.lowercase())
        .put("sections", org.json.JSONArray().apply {
            s.sections.forEach { sec ->
                put(org.json.JSONObject().put("kind", sec.kind.name.lowercase()).put("text", sec.text ?: org.json.JSONObject.NULL).put("shots", org.json.JSONArray().apply {
                    sec.shots.forEach { sh -> val b = sh.board!!; put(org.json.JSONObject().put("id", sh.id).put("action", b.action).put("framing", b.framing).put("camera", b.camera).put("mount", b.mount).put("riding", b.riding).put("sec", b.durationMs / 1000.0).put("audio", b.audio)
                        .put("match", org.json.JSONObject().put("events", org.json.JSONArray(b.match.events)).put("words", org.json.JSONArray(b.match.words)).put("engine", b.match.engine).put("mustRecord", b.match.mustRecord))
                        .apply { b.fallback?.let { f -> put("fallback", org.json.JSONObject().put("ideal", f.ideal).put("reason", f.reason).put("using", f.using)) } }) }
                }))
            }
        }).toString()

    @Test fun `a fresh script from the rider replaces the current one, hand edits and all`() {
        val base = StoryboardEngine.parse(first, null)!!.script!!
        val edited = base.copy(sections = base.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == "s1") it.copy(board = it.board!!.copy(action = "My edit"), locked = setOf(ShotFields.ACTION)) else it }) })
        val mine = """{"reply":"Built from your script.","fresh":true,"script":{"title":"Chai stop","format":"reel","sections":[{"kind":"hook","shots":[{"id":"s1","action":"Kettle steaming at the dhaba","sec":2}]}]}}"""
        val r = StoryboardEngine.parse(mine, edited)!!
        assertTrue(r.fresh)
        val next = StoryboardEngine.next(edited, r)!!
        assertEquals("Chai stop", next.title)
        assertEquals("Kettle steaming at the dhaba", next.sections.single().shots.single().board!!.action)
    }

    @Test fun `the prompt keeps to this chat, with no example topics and no older scripts' text`() {
        val style = com.ridetrack.app.studio.StyleContext(listOf("short punchy lines"), listOf(com.ridetrack.app.studio.StyleExample("Into the tunnel", "Tunnel time", null)))
        val p = StoryboardEngine.prompt(StoryboardChat("c", "t", 0, 0), Gear(), style, "a reel about my chai stop", firstChat = false)
        assertTrue("short punchy lines" in p)
        assertTrue("tunnel" !in p.lowercase(), "an example or an old edit leaks a topic")
        assertTrue("\"fresh\"" in p)
    }
}
