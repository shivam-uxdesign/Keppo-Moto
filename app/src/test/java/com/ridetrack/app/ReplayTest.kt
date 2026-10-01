package com.ridetrack.app

import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.common.easeAngle
import com.ridetrack.app.ui.common.positionAtSmooth
import com.ridetrack.app.ui.common.valueAtSmooth
import com.ridetrack.app.ui.detail.MomentReplay
import com.ridetrack.app.ui.detail.MomentWindow
import com.ridetrack.app.ui.detail.PHOTO_SHOW_MS
import com.ridetrack.app.ui.detail.spreadApart
import com.ridetrack.telemetry.model.TelemetrySample
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplayTest {
    private fun sample(t: Long, lat: Double?, lon: Double?, lean: Double? = null) =
        TelemetrySample(t, lat, lon, null, null, null, null, null, lean, null)

    private val samples = listOf(
        sample(0, 10.0, 20.0, lean = -10.0),
        sample(1_000, 10.001, 20.002, lean = 20.0),
        sample(2_000, null, null, lean = null),
        sample(3_000, 10.003, 20.006),
    )

    @Test
    fun `position blends between fixes`() {
        val p = assertNotNull(samples.positionAtSmooth(250.0))
        assertEquals(10.00025, p.latitude, 1e-9)
        assertEquals(20.0005, p.longitude, 1e-9)
        // At a sample, exactly that sample.
        assertEquals(10.001, samples.positionAtSmooth(1_000.0)!!.latitude, 1e-12)
    }

    @Test
    fun `position holds the last fix across a gap`() {
        val p = assertNotNull(samples.positionAtSmooth(1_500.0))
        assertEquals(10.001, p.latitude, 1e-12)
        assertEquals(10.003, samples.positionAtSmooth(9_000.0)!!.latitude, 1e-12)
    }

    @Test
    fun `values blend and stop where not recorded`() {
        assertEquals(5.0, samples.valueAtSmooth(500.0) { it.leanDeg }!!, 1e-9)
        assertEquals(20.0, samples.valueAtSmooth(1_500.0) { it.leanDeg }!!, 1e-9)
        assertNull(samples.valueAtSmooth(2_500.0) { it.leanDeg })
    }

    @Test
    fun `heading eases the short way round`() {
        assertEquals(0f, easeAngle(350f, 10f, 0.5f), 1e-4f)
        assertEquals(355f, easeAngle(5f, 345f, 0.5f), 1e-4f)
        assertEquals(10f, easeAngle(350f, 10f, 1f), 1e-4f)
        assertEquals(45f, easeAngle(0f, 90f, 0.5f), 1e-4f)
    }

    private fun moment(id: String, kind: MomentKind, time: Long, clipStart: Long? = null, duration: Long? = null) = Moment(
        id = id, rideId = "r", kind = kind, types = emptySet(), timeMillis = time, latitude = null, longitude = null,
        speedMps = null, peakValue = null, file = File("x"), thumb = null, durationMillis = duration, starred = false, clipStartMillis = clipStart,
    )

    @Test
    fun `clip and photo windows`() {
        assertEquals(MomentWindow("c", 50_000, 62_000), MomentWindow.of(moment("c", MomentKind.CLIP, 60_000, clipStart = 50_000, duration = 12_000)))
        assertEquals(MomentWindow("p", 70_000, 70_000 + PHOTO_SHOW_MS), MomentWindow.of(moment("p", MomentKind.PHOTO, 70_000)))
    }

    @Test
    fun `fast replay stops exactly at a moment and plays it at 1x`() {
        val r = MomentReplay(listOf(MomentWindow("a", 10_000, 14_000)))
        // 60x for a 100 ms frame would jump 6 s past the start.
        var t = r.advance(5_000.0, 100.0, 60.0)
        assertEquals(10_000.0, t)
        assertEquals("a", r.active?.id)
        t = r.advance(t, 100.0, 60.0)
        assertEquals(10_100.0, t)
        // Runs out at the window end, then back to full speed.
        t = r.advance(13_950.0, 100.0, 60.0)
        assertEquals(14_000.0, t)
        assertNull(r.active)
        assertEquals(20_000.0, r.advance(t, 100.0, 60.0))
    }

    @Test
    fun `skip continues after the moment and it does not come back until reset`() {
        val r = MomentReplay(listOf(MomentWindow("a", 10_000, 14_000)))
        r.advance(9_000.0, 2_000.0, 1.0)
        assertEquals(14_000.0, r.skip())
        assertNull(r.active)
        // Going back over it: already seen.
        assertEquals(12_000.0, r.advance(8_000.0, 1_000.0, 4.0))
        assertNull(r.active)
        r.reset()
        assertEquals(10_000.0, r.advance(8_000.0, 1_000.0, 4.0))
        assertEquals("a", r.active?.id)
    }

    @Test
    fun `skip all plays straight through until switched back on`() {
        val r = MomentReplay(listOf(MomentWindow("a", 10_000, 14_000), MomentWindow("b", 30_000, 34_000)))
        r.enabled = false
        assertEquals(60_000.0, r.advance(0.0, 1_000.0, 60.0))
        assertNull(r.active)
        r.enabled = true
        r.reset()
        assertEquals(10_000.0, r.advance(0.0, 1_000.0, 60.0))
    }

    @Test
    fun `each moment on the way stops the replay in turn`() {
        val r = MomentReplay(listOf(MomentWindow("b", 30_000, 34_000), MomentWindow("a", 10_000, 14_000)))
        assertEquals(10_000.0, r.advance(0.0, 1_000.0, 60.0))
        r.skip()
        assertEquals(30_000.0, r.advance(14_000.0, 1_000.0, 60.0))
        assertEquals("b", r.active?.id)
    }

    @Test
    fun `thumbnails are pushed apart and kept inside the row`() {
        // Three 32 px items wanting the same spot, 4 px gaps.
        assertContentEquals(intArrayOf(84, 120, 156), spreadApart(floatArrayOf(100f, 100f, 100f), intArrayOf(32, 32, 32), 4, 400))
        // Near the right edge they are pulled back in.
        assertContentEquals(intArrayOf(332, 368), spreadApart(floatArrayOf(395f, 399f), intArrayOf(32, 32), 4, 400))
        // Near the left edge they start at 0.
        assertContentEquals(intArrayOf(0, 36), spreadApart(floatArrayOf(0f, 2f), intArrayOf(32, 32), 4, 400))
    }
}
