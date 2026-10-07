package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionKind
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ClipTools
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StickerKind
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TextAnim
import com.ridetrack.app.studio.TextLook
import com.ridetrack.app.studio.TextTools
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextToolsTest {
    private fun bit(id: String, clipDur: Long = 10_000) = Bit("$id#0", id, clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 2_000, 4_000, listOf(CaptionLine(500, 1_500, "Arre yaar")), hook = true)
        val b = ClipSegment(bit("b"), 1_000, 3_000, emptyList(), hook = false)
        return TimelineEdits.addText(StudioPlan(listOf(a, b, StatsSegment(1_500)), Vibe.HYPE), 1_000, "Sixty five!", "t1")
    }

    @Test
    fun `text goes anywhere, any size and angle, kept on screen`() {
        var p = TextTools.place(plan(), "t1", 1.4f, 0.2f)
        p = TextTools.scale(p, "t1", 2f)
        p = TextTools.rotate(p, "t1", 200f)
        val t = p.texts.single()
        assertEquals(0.95f to 0.2f, t.x to t.y)
        assertEquals(2f, t.size)
        assertEquals(-160f, t.rotation)
    }

    @Test
    fun `animations bring text in and out`() {
        val t = TextTools.anim(TextTools.look(plan(), "t1", TextLook.OUTLINE), "t1", TextAnim.TYPE, TextAnim.FADE).texts.single()
        // Typing on: half the letters at 0.3 s.
        assertEquals(5, TextTools.animate(t, 300, 2_500, 11).chars)
        assertEquals(11, TextTools.animate(t, 1_000, 2_500, 11).chars)
        // Fading out at the end.
        assertTrue(TextTools.animate(t, 2_400, 2_500, 11).alpha < 0.5f)
        // Popping in starts small.
        val pop = TextTools.anim(plan(), "t1", TextAnim.POP).texts.single()
        assertTrue(TextTools.animate(pop, 30, 2_500, 11).scale < 0.8f)
    }

    @Test
    fun `new text takes the last text's look`() {
        val last = TextTools.color(TextTools.look(plan(), "t1", TextLook.BOX), "t1", 0xFFFFC83D.toInt()).texts.single()
        val next = TextTools.styled(plan().texts.single().copy(id = "t2"), last)
        assertEquals(TextLook.BOX, next.look)
        assertEquals(0xFFFFC83D.toInt(), next.color)
    }

    @Test
    fun `reading a clip again gives its segments the new words, at their speed`() {
        val p = ClipTools.speed(plan(), 0, 2f)
        val read = TextTools.relines(p, "a", listOf(CaptionLine(2_400, 3_000, "Bhai dekho"), CaptionLine(8_000, 9_000, "later")))
        val a = read.segments[0] as ClipSegment
        // 2.4 s into the clip is 0.4 s into the part, 0.2 s at 2x.
        assertEquals(listOf(200L to 500L), a.lines.map { it.startMs to it.endMs })
    }

    @Test
    fun `live stickers run to the end, emoji for a moment`() {
        var p = TextTools.addSticker(plan(), StickerKind.SPEED, 1_000, "s1")
        p = TextTools.addSticker(p, StickerKind.EMOJI, 2_000, "s2", "🔥")
        assertEquals(p.totalMs, p.stickers[0].endMs)
        assertEquals(4_500, p.stickers[1].endMs)
        p = TextTools.scaleSticker(TextTools.placeSticker(p, "s2", 0.3f, 0.3f), "s2", 1.5f)
        assertEquals(1.5f, p.stickers[1].size)
        // Shortening the Reel keeps them inside.
        val short = TimelineEdits.delete(p, 1)
        assertTrue(short.stickers.all { it.endMs <= short.totalMs })
    }

    @Test
    fun `text styles, the caption look and stickers are saved with the Reel`() {
        var plan = TextTools.anim(TextTools.color(TextTools.look(plan(), "t1", TextLook.HAND), "t1", -1), "t1", TextAnim.BOUNCE, TextAnim.SLIDE)
        plan = TextTools.captionLook(plan) { it.copy(kind = CaptionKind.PLAIN, size = 1.3f, y = 0.8f, karaoke = false) }
        plan = TextTools.addSticker(plan, StickerKind.LEAN, 0, "s1")
        plan = TextTools.addSticker(plan, StickerKind.EMOJI, 500, "s2", "🏍️")
        val p = ReelProject(
            id = "r", rideId = null, createdAt = 1, updatedAt = 1, title = "t", series = "", episode = 1, hookLine = "", postCaption = "", story = null,
            options = StudioOptions(), musicUri = null, musicName = null, plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs,
        )
        assertEquals(p, ReelJson.read(ReelJson.write(p)))
    }
}
