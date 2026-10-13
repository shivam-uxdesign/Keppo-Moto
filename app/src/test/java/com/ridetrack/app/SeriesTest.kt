package com.ridetrack.app

import com.ridetrack.app.studio.Footage
import com.ridetrack.app.studio.IdeaGroups
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptJson
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.ScriptWriter
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.Series
import com.ridetrack.app.studio.SeriesText
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TopSlots
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesTest {
    private val commute = Series(
        "se-1", "Delhi Commute Diaries", "One funny thing from the commute, Hinglish", PieceFormat.SHORT, 20, 30,
        styleId = "st-x", captionTemplate = "{name} · Ep {ep}\n{caption}\n#motovlog", nextEpisode = 4, notes = listOf("End on Kal milte hain"),
    )

    private fun sc(title: String, format: PieceFormat, vararg clips: String, series: String? = null) = Script(
        format, title, null, 45, "story", null, null, listOf(Section(SectionKind.HOOK, "sound", clips.map { ScriptShot(it, 0, 5_000) })), series = series,
    )

    @Test
    fun `series are kept and read back, bad lengths fixed`() {
        val back = SeriesText.read(SeriesText.write(listOf(commute)))
        assertEquals(listOf(commute), back)
        val odd = SeriesText.read("""[{"id":"a","name":"X","format":"CAPTION","min":2,"max":999}]""").single()
        assertEquals(PieceFormat.CAPTION.minSec, odd.minSec)
        assertEquals(PieceFormat.CAPTION.maxSec, odd.maxSec)
        assertTrue(SeriesText.read("not json").isEmpty())
    }

    @Test
    fun `an episode gets the series' format, length, look and caption`() {
        val ep = SeriesText.asEpisode(sc("Police ne roka", PieceFormat.REEL, "a"), commute)
        assertEquals(PieceFormat.SHORT, ep.format)
        assertEquals(30, ep.lengthSec)
        assertEquals("st-x", ep.style)
        assertEquals("se-1", ep.series)
        assertEquals("se-1", ScriptJson.read(ScriptJson.write(ep))?.series)
        assertEquals("Delhi Commute Diaries · Ep 4\nPolice?\n#motovlog", SeriesText.caption(commute, 4, "Police?"))
        assertEquals("Delhi Commute Diaries · Ep 2\nOwn words", SeriesText.caption(commute.copy(captionTemplate = ""), 2, "Own words"))
    }

    @Test
    fun `episodes keep their own cards and are never slots, clips may be shared`() {
        val list = listOf(
            sc("Chain", PieceFormat.REEL, "a", "b"),
            sc("Chain, the episode", PieceFormat.SHORT, "a", series = "se-1"),
            sc("Police", PieceFormat.CAPTION, "e"),
        )
        val g = IdeaGroups.group(list) { it }
        assertEquals(listOf("Chain", "Chain, the episode", "Police"), g.map { it.main.title })
        val top = TopSlots.pick(g, { it }) { true }
        assertFalse(top.any { it.second.series != null })
    }

    @Test
    fun `the series prompt carries the brief, notes and the way out`() {
        val fs = listOf(Footage("c1", "m1", 20_000, 0, "selfie", emptyList(), 50))
        val p = ScriptWriter.seriesPrompt(commute, "Monday", "30 km", fs, null)
        assertTrue(p.contains("One funny thing from the commute"))
        assertTrue(p.contains("End on Kal milte hain"))
        assertTrue(p.contains("{\"pieces\":[]}"))
        assertTrue(p.contains("Write ONE episode"))
        assertTrue(ScriptWriter.seriesPrompt(commute.copy(perRide = 0), "Monday", "30 km", fs, null).contains("at most 4"))
    }

    @Test
    fun `reels that look alike are offered as a series`() {
        fun reel(id: String, format: PieceFormat, seriesId: String? = null) = ReelProject(
            id, "r", 0, 0, id, "", 1, "", "", null, StudioOptions(vibe = Vibe.CHILL), null, null, StudioPlan(emptyList(), Vibe.CHILL), emptyList(), emptyList(), 20_000,
            script = sc(id, format, "a"), seriesId = seriesId,
        )
        val reels = (1..4).map { reel("c$it", PieceFormat.CAPTION) } + reel("r1", PieceFormat.REEL) + reel("s1", PieceFormat.CAPTION, seriesId = "se-1")
        val alike = SeriesText.alike(reels)
        assertEquals(listOf(listOf("c1", "c2", "c3", "c4")), alike.map { g -> g.map { it.id } })
        assertTrue(SeriesText.briefFrom(alike.single()).contains("caption clip"))
        // Kept with the Reel.
        assertEquals("se-1", ReelJson.read(ReelJson.write(reels.last()))?.seriesId)
        assertNull(ReelJson.read(ReelJson.write(reels.first()))?.seriesId)
    }
}
