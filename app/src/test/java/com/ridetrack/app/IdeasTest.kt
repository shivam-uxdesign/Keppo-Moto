package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.Footage
import com.ridetrack.app.studio.IdeaGroups
import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptJson
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.ScriptWriter
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.TopSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeasTest {
    private fun sc(title: String, format: PieceFormat, vararg clips: String, every: Boolean = false, variationOf: String? = null, ms: Long = 5_000) = Script(
        format, title, null, 20, "story", null, null,
        listOf(Section(SectionKind.HOOK, "sound", clips.map { ScriptShot(it, 0, ms) })),
        every = every, variationOf = variationOf,
    )

    @Test
    fun `ideas on the same moment share a card, others keep their own`() {
        val list = listOf(
            sc("Chain Loose Phir Se", PieceFormat.REEL, "a", "b", "c", "d"),
            sc("Morning Traffic Push", PieceFormat.CAPTION, "e"),
            sc("Na dhyan na diya but…", PieceFormat.SHORT, "a", every = true), // opens on the same clip
            sc("Traffic police:", PieceFormat.CAPTION, "e", every = true), // the same single clip
            sc("Roti kharab", PieceFormat.SHORT, "f", every = true),
            sc("Most of the chain", PieceFormat.STORY, "x", "b", "c", "d"), // shares 3 of 5 clips
        )
        val g = IdeaGroups.group(list) { it }
        assertEquals(listOf("Chain Loose Phir Se", "Morning Traffic Push", "Roti kharab"), g.map { it.main.title })
        assertEquals(listOf("Na dhyan na diya but…", "Most of the chain"), g[0].variations.map { it.title })
        assertEquals(listOf("Traffic police:"), g[1].variations.map { it.title })
    }

    @Test
    fun `a long reel keeps its own card, and another take goes under the one it was asked from`() {
        val list = listOf(
            sc("Recap", PieceFormat.LONG, "a", "b", "c", "d", "e"),
            sc("Chain", PieceFormat.REEL, "a", "b"),
            sc("Police", PieceFormat.CAPTION, "e"),
            sc("Chain, funnier", PieceFormat.SHORT, "z", variationOf = "Chain"),
        )
        val g = IdeaGroups.group(list) { it }
        assertEquals(listOf("Recap", "Chain", "Police"), g.map { it.main.title })
        assertTrue(g[0].variations.isEmpty())
        assertEquals(listOf("Chain, funnier"), g[1].variations.map { it.title })
        // Saved with the plan, so it stays under its card.
        assertEquals("Chain", ScriptJson.read(ScriptJson.write(list[3]))?.variationOf)
    }

    @Test
    fun `the top 3 are a long reel, the best moment and your story, each from its own card`() {
        val list = listOf(
            sc("Chain", PieceFormat.REEL, "a", "b"),
            sc("Recap", PieceFormat.LONG, "a", "b", "c", "d", "e"),
            sc("Police", PieceFormat.CAPTION, "e"),
            sc("Chain, short", PieceFormat.SHORT, "a"), // a variation of Chain: never a slot of its own
        )
        val g = IdeaGroups.group(list) { it }
        val talking = setOf("a")
        val top = TopSlots.pick(g, { it }) { s -> s.clips.any { it in talking } }
        assertEquals(
            listOf(TopSlots.Slot.LONG to "Recap", TopSlots.Slot.MOMENT to "Police", TopSlots.Slot.STORY to "Chain"),
            top.map { (slot, s) -> slot to s.title },
        )
        // No slot fits: the top card alone.
        val quiet = IdeaGroups.group(listOf(sc("Ride", PieceFormat.REEL, "q", ms = 40_000))) { it }
        assertEquals(listOf("Ride"), TopSlots.pick(quiet, { it }) { false }.map { it.second.title })
        // Nothing talks and no long reel: only the best moment.
        val one = IdeaGroups.group(listOf(sc("Reel", PieceFormat.REEL, "q"), sc("Cap", PieceFormat.CAPTION, "r"))) { it }
        assertEquals(listOf(TopSlots.Slot.MOMENT), TopSlots.pick(one, { it }) { false }.map { it.first })
    }

    @Test
    fun `a long reel is asked for when there's a minute of clips, talking or not`() {
        fun clip(k: String, ms: Long) = Footage(k, "m$k", ms, 0, "road", emptyList(), 60)
        val quiet = listOf(clip("c1", 30_000), clip("c2", 30_000), clip("c3", 20_000))
        val p = ScriptWriter.planPrompt("Monday", "30 km", quiet, null, PieceFormat.entries)
        assertTrue(p.contains("Include exactly one long reel"))
        assertTrue(p.contains("long (long Instagram Reel, 1–2 min"))
        assertFalse(p.contains("2+ minutes"))
        val thin = listOf(clip("c1", 20_000), clip("c2", 15_000), Footage("c3", "m3", 10_000, 0, "selfie", listOf(CaptionLine(0, 9_000, "bhai")), 30))
        val t = ScriptWriter.planPrompt("Monday", "30 km", thin, null, PieceFormat.entries)
        assertFalse(t.contains("long reel"))
        assertEquals(60, PieceFormat.LONG.minSec)
        assertEquals(120, PieceFormat.LONG.maxSec)
    }
}
