package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipInfo
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.Posting
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.Themes
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrowTest {
    private fun clip(id: String, ride: String, seen: List<String> = emptyList(), said: String? = null) =
        ClipInfo(id, ride, id.hashCode().toLong().and(0xffff), 12_000, said?.let { listOf(CaptionLine(2_000, 4_000, it)) }.orEmpty(), seen)

    @Test
    fun `things seen and phrases said across rides become compilations`() {
        val clips = listOf(
            clip("a", "r1", listOf("police", "traffic"), "bhai kya kar raha hai"),
            clip("b", "r1", listOf("police"), "arre bhai kya kar raha hai yaar"),
            clip("c", "r2", listOf("police", "rain")),
            clip("d", "r3", listOf("police"), "ye bhai kya kar raha hai"),
            clip("e", "r3", listOf("rain"), "bhai kya kar raha hai"),
            clip("f", "r1", listOf("road", "sky", "road")),
        )
        val t = Themes.find(clips)
        val police = t.first { it.label == "police" }
        assertEquals(4, police.hits.size)
        assertEquals("Police, 4 times", police.title)
        val said = t.filter { it.said }
        // The longest phrase said in the same 4 clips; its shorter parts aren't themes of their own.
        assertEquals(listOf("kya kar raha"), said.map { it.label }.filter { it == "kya kar raha" })
        assertTrue(said.none { it.label == "kya kar" || it.label == "bhai kya" && said.any { o -> o.label == "bhai kya kar" } })
        assertTrue(t.none { it.label == "road" || it.label == "rain" }) // plain, or too few
        // One ride only: not a theme.
        assertTrue(Themes.find(clips.map { it.copy(rideId = "r1") }).isEmpty())
        val sc = Themes.script(police)
        assertEquals(SectionKind.HOOK, sc.sections.first().kind)
        assertEquals(4, sc.sections.size)
    }

    private fun reel(format: PieceFormat, views: Int) = ReelProject(
        "$format$views", "r", 0, 0, "t", "", 1, "", "", null, StudioOptions(), null, null, StudioPlan(emptyList(), Vibe.CHILL), emptyList(), emptyList(), 20_000,
        views = views, script = Script(format, "t", null, 20, "story", null, null, emptyList()),
    )

    private fun sc(title: String, f: PieceFormat, variationOf: String? = null, every: Boolean = false) =
        Script(f, title, null, 20, "story", null, null, listOf(Section(SectionKind.HOOK, "x", listOf(ScriptShot(title, 0, 1_000)))), every = every, variationOf = variationOf)

    @Test
    fun `what did well leads the next ride's ideas`() {
        val reels = listOf(reel(PieceFormat.CAPTION, 9_000), reel(PieceFormat.CAPTION, 7_000), reel(PieceFormat.REEL, 1_000), reel(PieceFormat.REEL, 1_200), reel(PieceFormat.SHORT, 800))
        val b = Posting.boosts(reels)
        assertTrue(b.getValue(PieceFormat.CAPTION) > 2.0)
        assertNull(b[PieceFormat.SHORT]) // one isn't enough
        assertEquals("Caption clips get you 2.1× your usual views.", Posting.lesson(b))
        val ranked = Posting.rank(
            listOf(sc("Reel A", PieceFormat.REEL), sc("Reel A · other hook", PieceFormat.REEL, variationOf = "Reel A"), sc("Cap", PieceFormat.CAPTION), sc("Every", PieceFormat.SHORT, every = true)),
            b,
        )
        assertEquals(listOf("Cap", "Reel A", "Reel A · other hook", "Every"), ranked.map { it.title })
        assertTrue(Posting.performance(reels).contains("Suggest more of those"))
        // Too few with views: nothing learnt, order kept.
        assertTrue(Posting.boosts(reels.take(3)).isEmpty())
    }
}
