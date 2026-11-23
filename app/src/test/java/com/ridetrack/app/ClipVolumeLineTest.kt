package com.ridetrack.app

import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.Ease
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StatsSegment
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.studio.TrackEdits
import com.ridetrack.app.studio.TrackKind
import com.ridetrack.app.studio.Vibe
import com.ridetrack.app.studio.VolumeCurve
import com.ridetrack.app.studio.VolumePoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #21: the volume line on a clip's own sound (Mic 1). */
class ClipVolumeLineTest {
    private fun bit(id: String) = Bit("$id#0", id, 10_000, 0, 10_000, 1_000_000, emptyList(), 40.0, 3f)
    private fun near(a: Float, b: Float) = assertTrue(abs(a - b) < 0.01f, "$a vs $b")

    /** Clip a 4 s with a dip: full, down to 20% at 2 s, back to full at 4 s. */
    private fun plan(): StudioPlan {
        val a = ClipSegment(bit("a"), 1_000, 4_000, emptyList(), hook = true, curve = listOf(VolumePoint(0, 1f), VolumePoint(2_000, 0.2f), VolumePoint(4_000, 1f)))
        val b = ClipSegment(bit("b"), 0, 3_000, emptyList(), hook = false)
        return StudioPlan(listOf(a, b, StatsSegment(1_500)), Vibe.HYPE)
    }

    private fun clip(p: StudioPlan, i: Int) = p.segments[i] as ClipSegment

    @Test
    fun `the line's level between points follows each point's ease`() {
        val c = listOf(VolumePoint(0, 0f, Ease.LINEAR), VolumePoint(1_000, 1f, Ease.HOLD), VolumePoint(2_000, 0f))
        near(0.5f, VolumeCurve.at(c, 500))
        // Hold: stays until the next point.
        near(1f, VolumeCurve.at(c, 1_900))
        near(0f, VolumeCurve.at(c, 5_000))
        near(1f, VolumeCurve.at(emptyList(), 300))
    }

    @Test
    fun `points are added, moved, eased and deleted on the clip`() {
        var p = TimelineEdits.addClipPoint(plan(), 1, 1_000, 0.5f)
        assertEquals(listOf(VolumePoint(1_000, 0.5f, Ease.SMOOTH)), clip(p, 1).curve)
        // Within 100 ms of a point: it gives way.
        p = TimelineEdits.addClipPoint(p, 1, 1_050, 0.8f)
        assertEquals(1, clip(p, 1).curve.size)
        p = TimelineEdits.addClipPoint(p, 1, 2_500, 1.4f)
        p = TimelineEdits.moveClipPoint(p, 1, 1, 200, 2f)
        // Moved before the other, and capped at 150%.
        assertEquals(VolumePoint(200, 1.5f, Ease.SMOOTH), clip(p, 1).curve.first())
        p = TimelineEdits.easeClipPoint(p, 1, 0, Ease.HOLD)
        assertEquals(Ease.HOLD, clip(p, 1).curve.first().ease)
        p = TimelineEdits.deleteClipPoint(p, 1, 0)
        assertEquals(1, clip(p, 1).curve.size)
        assertTrue(clip(TimelineEdits.clearClipCurve(p, 1), 1).curve.isEmpty())
    }

    @Test
    fun `splitting keeps the level where the cut is, on both halves`() {
        val p = TimelineEdits.split(plan(), 1_000)
        val a = clip(p, 0)
        val b = clip(p, 1)
        assertEquals(1_000, a.durMs)
        // The first half ends at the level the line had at 1 s; the second starts there.
        near(VolumeCurve.at(clip(plan(), 0).curve, 1_000), VolumeCurve.at(a.curve, 1_000))
        near(0.6f, VolumeCurve.at(b.curve, 0))
        near(0.2f, VolumeCurve.at(b.curve, 1_000))
        near(1f, VolumeCurve.at(b.curve, 3_000))
    }

    @Test
    fun `trimming the start or end keeps the rest of the line where it was`() {
        val start = clip(TimelineEdits.trimStart(plan(), 0, 1_000), 0)
        assertEquals(3_000, start.durMs)
        near(0.6f, VolumeCurve.at(start.curve, 0))
        near(0.2f, VolumeCurve.at(start.curve, 1_000))
        val end = clip(TimelineEdits.trimEnd(plan(), 0, -1_000), 0)
        near(0.2f, VolumeCurve.at(end.curve, 2_000))
        near(0.6f, VolumeCurve.at(end.curve, 3_000))
    }

    @Test
    fun `detaching the sound takes its line along`() {
        val p = TrackEdits.detach(plan(), 0, "d1")
        val a = p.audio.single { it.kind == TrackKind.DETACHED }
        assertEquals(3, a.curve.size)
        assertTrue(clip(p, 0).curve.isEmpty())
        near(0.2f * a.volume, TrackEdits.levelAt(a, 2_000))
    }

    @Test
    fun `a clip's line is saved and read back, and projects without one open as before`() {
        val plan = plan()
        val p = ReelProject(
            id = "r", rideId = null, createdAt = 1, updatedAt = 1, title = "t", series = "", episode = 1, hookLine = "", postCaption = "", story = null,
            options = StudioOptions(), musicUri = null, musicName = null, plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs,
        )
        val back = ReelJson.read(ReelJson.write(p))
        assertEquals(clip(p.plan, 0).curve, clip(back!!.plan, 0).curve)
        assertTrue(clip(back.plan, 1).curve.isEmpty())
    }
}
