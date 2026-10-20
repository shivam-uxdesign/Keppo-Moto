package com.ridetrack.app

import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ClipTools
import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.Vibe
import com.ridetrack.app.studio.storyboard.AutoTrim
import com.ridetrack.app.studio.storyboard.Candidate
import com.ridetrack.app.studio.storyboard.MatchHints
import com.ridetrack.app.studio.storyboard.ShotEdits
import com.ridetrack.app.studio.storyboard.ShotFields
import com.ridetrack.app.studio.storyboard.Storyboard
import com.ridetrack.app.studio.storyboard.StoryboardEngine
import com.ridetrack.app.studio.storyboard.Suggest
import com.ridetrack.app.studio.storyboard.TemplateBuilder
import com.ridetrack.app.studio.storyboard.ToFilm
import com.ridetrack.app.studio.storyboard.VoiceLines
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class StoryboardFillTest {
    private val script = StoryboardEngine.parse(
        """{"reply":"ok","script":{"title":"Tunnel","format":"reel","sections":[
            {"kind":"hook","text":"How to enter a tunnel","shots":[{"id":"s1","action":"The tunnel mouth","camera":"phone back","mount":"tripod at the roadside","riding":false,"sec":2}]},
            {"kind":"setup","text":"STEP 1","shots":[{"id":"s2","action":"Eyes lock on","camera":"phone front","mount":"handlebar","sec":1.5,"match":{"mustRecord":true}},
                                                   {"id":"s3","action":"Full throttle into the tunnel","camera":"phone back","mount":"handlebar","sec":3,"audio":"engine","match":{"events":["strong acceleration"],"words":["tunnel"],"engine":true}}]}],
            "texts":[{"text":"GO","shot":"s3","sec":1}]}}""",
        null,
    )!!.script!!

    private fun board(id: String) = script.sections.flatMap { it.shots }.first { it.id == id }.board!!

    @Test fun `a long clip is cut around its moment, a short one gently slowed`() {
        assertEquals(AutoTrim.Fit(4_000, 1f, 2_000, slowed = false, shrunk = false), AutoTrim.fit(10_000, 2_000, centreMs = 5_000))
        assertEquals(0L, AutoTrim.fit(10_000, 2_000, centreMs = 200).inMs)
        val gentle = AutoTrim.fit(1_800, 2_000)
        assertEquals(2_000, gentle.durMs); assertTrue(!gentle.slowed)
        assertTrue(AutoTrim.fit(1_500, 2_000).slowed)
        val tiny = AutoTrim.fit(700, 2_000)
        assertEquals(AutoTrim.MOST, tiny.speed); assertTrue(tiny.shrunk && tiny.durMs == 1_000L)
        // Engine sound isn't slowed: the shot gets shorter instead.
        assertEquals(AutoTrim.Fit(0, 1f, 1_500, slowed = false, shrunk = true), AutoTrim.fit(1_500, 2_000, engineFirst = true))
    }

    @Test fun `suggestions follow the shot's events, words, camera and sound, never for must-record`() {
        val good = Candidate("m1", "r1", 5_000, setOf("strong acceleration"), setOf("tunnel"), "back", hasEngine = true)
        val selfie = Candidate("m2", "r1", 5_000, setOf("strong acceleration"), emptySet(), null, hasEngine = true)
        val quiet = Candidate("m3", "r2", 800, emptySet(), emptySet(), "back", hasEngine = false)
        val ranked = Suggest.rank(board("s3"), listOf(quiet, selfie, good))
        assertEquals("m1", ranked.first().first.momentId)
        assertTrue(ranked.none { it.first.momentId == "m3" })
        assertTrue(Suggest.rank(board("s2"), listOf(good, selfie)).isEmpty())
        assertTrue(Suggest.rank(board("s3"), listOf(good), exclude = setOf("m1")).isEmpty())
    }

    @Test fun `the to-film list groups shots by where the phone goes and off-bike shots come first`() {
        val shots = listOf("s1", "s2", "s3").map { it to board(it) } + ("s4" to board("s3").copy(action = "Exit"))
        val g = ToFilm.groups(shots)
        assertEquals("Tripod at the roadside", g.first().where)
        assertTrue(!g.first().riding)
        assertEquals(listOf("s3", "s4"), g.first { it.where == "Handlebar mount, back camera" }.shots.map { it.first })
        assertEquals(3, g.size)
    }

    @Test fun `the rider's own changes lock those fields, moves cross parts and delete keeps one`() {
        val e = ShotEdits.edit(script, "s1") { it.copy(action = "The tunnel at dawn", durationMs = 2_500) }
        val s1 = e.sections[0].shots[0]
        assertEquals(setOf(ShotFields.ACTION, ShotFields.DURATION), s1.locked)
        assertEquals(script.totalMs + 500, e.totalMs)
        val moved = ShotEdits.move(script, "s1", 1)
        assertEquals(listOf("s2"), moved.sections[0].shots.map { it.id })
        assertEquals(listOf("s1", "s3"), moved.sections[1].shots.map { it.id })
        val added = ShotEdits.add(script, "s2")
        assertEquals(3, added.sections[1].shots.size)
        var one = script
        listOf("s1", "s2", "s3").forEach { one = ShotEdits.delete(one, it) }
        assertEquals(1, one.sections.sumOf { it.shots.size })
    }

    @Test fun `a template is a placeholder per shot with its texts and filling one swaps in footage`() {
        val pics = listOf("s1", "s2", "s3").associateWith { File("/tmp/ph-$it.png") }
        val plan = TemplateBuilder.plan(script, pics, Vibe.CINE, 0)
        assertEquals(listOf("s1", "s2", "s3"), TemplateBuilder.placeholders(plan))
        assertEquals(6_500, plan.totalMs)
        assertTrue(plan.clips.all { it.still != null })
        assertTrue(plan.texts.any { it.text == "GO" && it.startMs == 3_500L })
        assertTrue(plan.texts.any { it.text == "STEP 1" && it.startMs == 2_000L })

        // The editor's Replace on a placeholder fits the clip to the shot's slot.
        val bit = Bit("m9#0", "m9", 10_000, 0, 10_000, 0, emptyList(), 0.0, 5f)
        val i = plan.segments.indexOfFirst { it is ClipSegment && TemplateBuilder.shotIdOf(it.bit) == "s3" }
        val filled = ClipTools.replace(plan, i, bit)
        val seg = filled.segments[i] as ClipSegment
        assertEquals("m9", seg.bit.momentId)
        assertEquals(3_000, seg.durMs)
        assertEquals(3_500L, seg.inMs)
        assertEquals(null, seg.still)
        assertEquals(listOf("s1", "s2"), TemplateBuilder.placeholders(filled))
    }

    @Test fun `a storyboard with no matches stays empty-handed`() {
        val b = Storyboard("Rain on the visor", match = MatchHints(words = listOf("rain")))
        assertTrue(Suggest.rank(b, listOf(Candidate("x", null, 3_000, emptySet(), setOf("sun"), "back", false))).isEmpty())
    }

    @Test fun `lines read in one go are cut at the taps, trimmed, and land where their shots start`() {
        val said = script.copy(sections = script.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == "s2") it else it.copy(board = it.board!!.copy(say = "Line for ${it.id}")) }) })
        assertEquals(listOf("s1" to "Line for s1", "s3" to "Line for s3"), VoiceLines.lines(said))
        assertEquals(listOf(0L until 2_000L, 2_000L until 5_000L), VoiceLines.pieces(listOf(2_000), 5_000))
        // A tap outside the recording is ignored.
        assertEquals(1, VoiceLines.pieces(listOf(9_000), 5_000).size)

        val rate = 1_000
        val pcm = ShortArray(3_000).also { a -> for (i in 1_000 until 1_500) a[i] = 5_000 }
        val kept = VoiceLines.trim(pcm, 0, 3_000, rate)
        assertTrue(kept.first in 900..1_000 && kept.last in 1_500..1_600)
        assertTrue(VoiceLines.trim(ShortArray(2_000), 0, 2_000, rate).isEmpty())

        val plan = TemplateBuilder.plan(script, listOf("s1", "s2", "s3").associateWith { File("/tmp/ph-$it.png") }, Vibe.CINE, 0)
        assertEquals(3_500L, VoiceLines.shotStart(plan, emptyMap(), "s3"))
        assertEquals(3_000L, VoiceLines.shotLength(plan, emptyMap(), "s3"))
        val bit = Bit("m9#0", "m9", 10_000, 0, 10_000, 0, emptyList(), 0.0, 5f)
        val i = plan.segments.indexOfFirst { it is ClipSegment && TemplateBuilder.shotIdOf(it.bit) == "s1" }
        val filled = ClipTools.replace(plan, i, bit)
        assertEquals(0L, VoiceLines.shotStart(filled, mapOf("s1" to "m9"), "s1"))
    }
}
