package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.EditHistory
import com.ridetrack.app.studio.LayerPreset
import com.ridetrack.app.studio.LayerShape
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.TrackEdits
import com.ridetrack.app.studio.TrackKind
import com.ridetrack.app.studio.Vibe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TracksTest {
    private fun bit(id: String, clipDur: Long = 10_000) = Bit("$id#0", id, clipDur, 0, clipDur, 1_000_000, emptyList(), 40.0, 3f)

    /** Clip a 4 s (talking), clip b 3 s, clip c 2 s, stats 1.5 s, the loop end. */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 2_000, 4_000, listOf(CaptionLine(500, 1_800, "Arre yaar")), hook = true)
        val b = ClipSegment(bit("b"), 1_000, 3_000, emptyList(), hook = false)
        val c = ClipSegment(bit("c"), 0, 2_000, emptyList(), hook = false)
        val tail = ClipSegment(a.bit, a.inMs, 600, emptyList(), hook = false, tail = true)
        return StudioPlan(listOf(a, b, c, StatsSegment(1_500), tail), Vibe.HYPE)
    }

    @Test
    fun `a layer goes in at the playhead, as long as its clip, inside the Reel`() {
        val p = TrackEdits.addLayer(plan(), bit("road", 20_000).copy(inMs = 2_000, outMs = 8_000), 9_000, "l1")
        val l = p.layers.single()
        assertEquals(9_000, l.startMs)
        // 6 s of clip, but only 2.1 s of Reel left.
        assertEquals(p.totalMs - 9_000, l.durMs)
        assertEquals(LayerShape.ROUNDED, l.shape)
    }

    @Test
    fun `presets place the layer for picture-in-picture, halves and the stack`() {
        val p = TrackEdits.addLayer(plan(), bit("road"), 0, "l1")
        val top = TrackEdits.preset(p, "l1", LayerPreset.TOP).layers.single()
        assertEquals(0.25f, top.cy)
        assertEquals(1f, top.w)
        val right = TrackEdits.preset(p, "l1", LayerPreset.BOTTOM_RIGHT).layers.single()
        assertEquals(0.75f to 0.75f, right.cx to right.cy)
        assertEquals(0.5f, right.w)
    }

    @Test
    fun `move points ease the layer from one place to the next`() {
        var p = TrackEdits.addLayer(plan(), bit("road"), 0, "l1")
        p = TrackEdits.addKey(p, "l1", 2_000)
        p = TrackEdits.moveLayer(p, "l1", -0.4f, 0f, atMs = 2_000)
        val l = p.layers.single()
        assertEquals(2, l.keys.size)
        val start = TrackEdits.keyAt(l, 0)
        val mid = TrackEdits.keyAt(l, 1_000)
        val end = TrackEdits.keyAt(l, 3_000)
        assertEquals(0.72f, start.cx)
        assertTrue(abs(end.cx - 0.32f) < 0.001f, "${end.cx}")
        assertTrue(abs(mid.cx - 0.52f) < 0.001f, "${mid.cx}")
    }

    @Test
    fun `detaching a clip's sound mutes the clip and puts the sound on its own track`() {
        val p = TrackEdits.detach(plan(), 1, "d1")
        assertEquals(0f, (p.segments[1] as ClipSegment).volume)
        val d = p.audio.single()
        assertEquals(TrackKind.DETACHED, d.kind)
        assertEquals(4_000, d.startMs)
        assertEquals(3_000, d.durMs)
        assertEquals(0.55f, d.volume)
        // An L cut: the sound runs on past the cut, within its clip.
        val l = TrackEdits.resizeAudio(p, "d1", 1_500).audio.single()
        assertEquals(4_500, l.durMs)
        // Detaching again does nothing.
        assertEquals(p, TrackEdits.detach(p, 1, "d2"))
    }

    @Test
    fun `engine sound lines up with its clips and follows them when they move`() {
        var n = 0
        val p = TrackEdits.addEngine(plan(), mapOf("a" to "file:///a.engine.m4a", "c" to "file:///c.engine.m4a"), { "e${n++}" })
        assertEquals(listOf(0L to 4_000L, 7_000L to 2_000L), p.audio.map { it.startMs to it.durMs })
        // c moves before b: its engine sound moves with it.
        val moved = TimelineEdits.move(p, 1, 1)
        assertEquals(listOf(0L, 4_000L), moved.audio.map { it.startMs }.sorted())
    }

    @Test
    fun `levels follow the volume, the curve and the fades`() {
        var p = TrackEdits.detach(plan(), 0, "d")
        p = TrackEdits.fade(p, "d", 500, 1_000)
        p = TrackEdits.addVolumePoint(p, "d", 0, 1f)
        p = TrackEdits.addVolumePoint(p, "d", 2_000, 0.5f)
        val a = p.audio.single()
        assertEquals(0f, TrackEdits.levelAt(a, 0))
        assertEquals(0.875f, TrackEdits.levelAt(a, 500))
        assertEquals(0.75f, TrackEdits.levelAt(a, 1_000))
        assertEquals(0.25f, TrackEdits.levelAt(a, 3_500))
    }

    @Test
    fun `mute and solo decide what each track plays at`() {
        var p = TrackEdits.trackVolume(plan(), TrackKind.MUSIC, 0.4f)
        assertEquals(0.4f, p.mix.gain(TrackKind.MUSIC))
        p = TrackEdits.solo(p, TrackKind.ENGINE)
        assertEquals(0f, p.mix.gain(TrackKind.MUSIC))
        assertEquals(1f, p.mix.gain(TrackKind.ENGINE))
        p = TrackEdits.mute(TrackEdits.solo(p, TrackKind.ENGINE), TrackKind.CLIPS)
        assertEquals(0f, p.mix.gain(TrackKind.CLIPS))
        assertEquals(0.4f, p.mix.gain(TrackKind.MUSIC))
    }

    @Test
    fun `several clips move and delete together`() {
        val (p, at) = TrackEdits.moveClips(plan(), setOf(1, 2), -1)
        assertEquals(listOf("b", "c", "a"), p.clips.map { it.bit.momentId })
        assertEquals(setOf(0, 1), at)
        // The first clip opens the Reel, and the loop end follows it.
        assertTrue((p.segments[0] as ClipSegment).hook)
        assertEquals("b", (p.segments.last() as ClipSegment).bit.momentId)
        val d = TrackEdits.deleteClips(plan(), setOf(0, 2))
        assertEquals(listOf("b"), d.clips.map { it.bit.momentId })
        // Never all of them.
        assertEquals(plan(), TrackEdits.deleteClips(plan(), setOf(0, 1, 2)))
    }

    @Test
    fun `settings paste onto other clips`() {
        val p = TimelineEdits.volume(plan(), 0, 0.3f)
        val s = TrackEdits.copySettings(p, 0)!!
        val pasted = TrackEdits.pasteSettings(p, setOf(1, 2), s)
        assertEquals(listOf(0.3f, 0.3f, 0.3f), pasted.clips.map { it.volume })
    }

    @Test
    fun `history names each step and goes back to any of them`() {
        val h = EditHistory(plan())
        h.apply(TimelineEdits.volume(h.current, 0, 0.5f), "Sound")
        h.apply(TrackEdits.addMarker(h.current, 1_000, "m"), "Marker")
        h.apply(TimelineEdits.delete(h.current, 2), "Delete")
        assertEquals(listOf("Sound", "Marker", "Delete"), h.steps)
        h.undoTo(1)
        assertEquals(listOf("Sound"), h.steps)
        assertTrue(h.current.markers.isEmpty())
        h.redo()
        assertEquals(1, h.current.markers.size)
    }

    @Test
    fun `shortening the Reel keeps layers and sounds inside it`() {
        var p = TrackEdits.addLayer(plan(), bit("road"), 8_000, "l1")
        p = TrackEdits.detach(p, 2, "d")
        val shorter = TimelineEdits.delete(p, 2)
        assertTrue(shorter.layers.all { it.endMs <= shorter.totalMs })
        assertTrue(shorter.audio.all { it.endMs <= shorter.totalMs })
    }

    @Test
    fun `layers, sounds, the mix and markers are saved with the Reel`() {
        var plan = TrackEdits.addLayer(plan(), bit("road"), 1_000, "l1", LayerPreset.BOTTOM_LEFT)
        plan = TrackEdits.addKey(plan, "l1", 1_500)
        plan = TrackEdits.detach(plan, 1, "d")
        plan = TrackEdits.addVolumePoint(plan, "d", 300, 0.8f)
        plan = TrackEdits.mute(TrackEdits.trackVolume(plan, TrackKind.MUSIC, 0.3f), TrackKind.ENGINE)
        plan = TrackEdits.cleanVoice(plan, true)
        plan = TrackEdits.addMarker(plan, 2_500, "m1")
        val p = ReelProject(
            id = "r", rideId = null, createdAt = 1, updatedAt = 1, title = "t", series = "", episode = 1, hookLine = "", postCaption = "", story = null,
            options = StudioOptions(), musicUri = null, musicName = null, plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs,
        )
        assertEquals(p, ReelJson.read(ReelJson.write(p)))
    }
}
