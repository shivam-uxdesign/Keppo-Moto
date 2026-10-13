package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.Lanes
import com.ridetrack.app.studio.Lanes.Span
import com.ridetrack.app.studio.LayerItem
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StickerItem
import com.ridetrack.app.studio.StickerKind
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TextItem
import com.ridetrack.app.studio.TrackEdits
import com.ridetrack.app.studio.Vibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanesTest {
    @Test
    fun `things that overlap go on rows of their own, ones that don't share a row`() {
        val rows = Lanes.pack(listOf(Span(0, 2_000), Span(1_000, 3_000), Span(2_000, 4_000), Span(500, 3_500)))
        // b overlaps a; c starts as a ends (no overlap); d overlaps all three.
        assertEquals(listOf(0, 1, 0, 2), rows.toList())
        assertEquals(3, Lanes.count(rows))
        assertEquals(0, Lanes.count(Lanes.pack(emptyList())))
    }

    @Test
    fun `moving one to another row changes as little of the order as it can`() {
        val spans = listOf(Span(0, 2_000), Span(1_000, 3_000), Span(1_500, 2_500))
        // c is on row 2; to row 0 it goes first, the others keep their order.
        assertEquals(listOf(2, 0, 1), Lanes.moveTo(spans, 2, 0))
        // To where it already is: nothing changes.
        assertEquals(listOf(0, 1, 2), Lanes.moveTo(spans, 2, 2))
    }

    private fun bit(id: String) = Bit("$id#0", id, 20_000, 0, 20_000, 0, emptyList(), 40.0, 3f)

    private fun layer(id: String, start: Long, dur: Long) = LayerItem(id, bit(id), 0, start, dur)

    private fun plan(layers: List<LayerItem> = emptyList(), texts: List<TextItem> = emptyList(), stickers: List<StickerItem> = emptyList()) =
        StudioPlan(listOf(ClipSegment(bit("a"), 0, 10_000, emptyList(), hook = true)), Vibe.HYPE, texts = texts, layers = layers, stickers = stickers)

    @Test
    fun `dragging a layer up the timeline puts it over the others`() {
        // The last layer is drawn on top: z is on top, so on the first row.
        val p = plan(listOf(layer("x", 0, 4_000), layer("y", 1_000, 4_000), layer("z", 2_000, 4_000)))
        val top = p.layers.asReversed().map { Lanes.Span(it.startMs, it.endMs) }
        assertEquals(listOf(0, 1, 2), Lanes.pack(top).toList())
        // x, on the bottom row, goes up two rows: now it's on top (last).
        val up = TrackEdits.stackLayer(p, "x", -2)
        assertEquals(listOf("y", "z", "x"), up.layers.map { it.id })
        // And back down one: under x's old neighbour z, over y.
        val down = TrackEdits.stackLayer(up, "x", 1)
        assertEquals(listOf("y", "x", "z"), down.layers.map { it.id })
        // Up past the top stays on top.
        assertEquals(up.layers, TrackEdits.stackLayer(up, "x", -5).layers)
    }

    @Test
    fun `text and stickers move rows the same way`() {
        val p = plan(
            texts = listOf(TextItem("t1", 0, 3_000, "one"), TextItem("t2", 1_000, 4_000, "two")),
            stickers = listOf(StickerItem("s1", StickerKind.EMOJI, 0, 3_000, text = "🔥"), StickerItem("s2", StickerKind.SPEED, 0, 3_000)),
        )
        assertEquals(listOf("t2", "t1"), TrackEdits.stackText(p, "t1", -1).texts.map { it.id })
        assertEquals(listOf("s2", "s1"), TrackEdits.stackSticker(p, "s1", -1).stickers.map { it.id })
        assertEquals(p, TrackEdits.stackText(p, "t1", 0))
    }

    @Test
    fun `hidden things stay on the timeline but out of the video, and are saved`() {
        var p = plan(listOf(layer("x", 0, 4_000)), listOf(TextItem("t1", 0, 3_000, "one")), listOf(StickerItem("s1", StickerKind.EMOJI, 0, 3_000, text = "🔥")))
        p = TrackEdits.hideSticker(TrackEdits.hideText(TrackEdits.hideLayer(p, "x", true), "t1", true), "s1", true)
        assertTrue(p.layers.single().hidden && p.texts.single().hidden && p.stickers.single().hidden)
        val shown = p.shown()
        assertTrue(shown.layers.isEmpty() && shown.texts.isEmpty() && shown.stickers.isEmpty())
        val back = TrackEdits.hideLayer(p, "x", false)
        assertEquals(1, back.shown().layers.size)
        val project = ReelProject(
            id = "r", rideId = null, createdAt = 1, updatedAt = 1, title = "t", series = "", episode = 1, hookLine = "", postCaption = "", story = null,
            options = StudioOptions(), musicUri = null, musicName = null, plan = p, takes = emptyList(), tips = emptyList(), durationMs = p.totalMs,
        )
        assertEquals(project, ReelJson.read(ReelJson.write(project)))
    }
}
