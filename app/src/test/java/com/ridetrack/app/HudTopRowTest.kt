package com.ridetrack.app

import com.ridetrack.app.hud.CameraIndicator
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.hud.HudTopRow
import com.ridetrack.app.hud.HudTopRow.Tone
import com.ridetrack.app.hud.HudVideo
import com.ridetrack.app.moments.MomentSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HudTopRowTest {
    private fun data(
        status: HudStatus = HudStatus.RECORDING,
        camera: CameraIndicator = CameraIndicator.ON,
        video: HudVideo? = null,
        clipStart: Long? = null,
        photoAt: Long? = null,
        photoTaken: Long? = null,
        now: Long = 100_000,
    ) = HudData(
        status = status, stoppedForMillis = 65_000, speedMps = null, leanDeg = null, leanNote = null, distanceM = null,
        elapsedMillis = 754_000, avgSpeedMps = null, maxSpeedMps = null, longitudinalG = null, combinedG = null,
        maxLeanDeg = null, headingDeg = null, demo = false, camera = camera, video = video,
        clipStartMillis = clipStart, photoAtMillis = photoAt, photoTakenAtMillis = photoTaken, nowMillis = now,
    )

    @Test
    fun `left shows the ride, or GPS lost while it is lost`() {
        assertEquals(HudTopRow.Label("Riding 12:34", Tone.TEXT), HudTopRow.left(data()))
        assertEquals(HudTopRow.Label("Stopped 01:05", Tone.PAUSED), HudTopRow.left(data(HudStatus.STOPPED)))
        assertEquals(HudTopRow.Label("GPS lost", Tone.WARNING), HudTopRow.left(data(HudStatus.GPS_LOST)))
        assertEquals(HudTopRow.Label("On a break 01:05", Tone.PAUSED), HudTopRow.left(data(HudStatus.BREAK)))
    }

    @Test
    fun `idle camera is a plain cam, grey when off`() {
        assertEquals(HudTopRow.Label("cam", Tone.MUTED), HudTopRow.right(data()))
        assertEquals(HudTopRow.Label("cam", Tone.DIM), HudTopRow.right(data(camera = CameraIndicator.OFF)))
    }

    @Test
    fun `event clip counts from its look-back`() {
        // The event was 0 s ago, the clip started 10 s back.
        assertEquals(HudTopRow.Label("cam 00:10", Tone.REC), HudTopRow.right(data(clipStart = 90_000)))
        assertEquals("cam 00:20", HudTopRow.right(data(clipStart = 80_000)).text)
    }

    @Test
    fun `manual video counts up from zero`() {
        val v = HudVideo(MomentSource.MANUAL, starting = false, paused = false, elapsedMillis = 0)
        assertEquals(HudTopRow.Label("cam 00:00", Tone.REC), HudTopRow.right(data(video = v)))
        assertEquals("cam 01:03", HudTopRow.right(data(video = v.copy(elapsedMillis = 63_500))).text)
    }

    @Test
    fun `photo counts three two one, says photo, then goes back to cam`() {
        assertEquals("3", HudTopRow.right(data(photoAt = 103_000)).text)
        assertEquals("2", HudTopRow.right(data(photoAt = 101_200)).text)
        assertEquals("1", HudTopRow.right(data(photoAt = 100_001)).text)
        assertEquals(HudTopRow.Label("photo", Tone.ACCENT), HudTopRow.right(data(photoTaken = 99_000)))
        assertEquals("cam", HudTopRow.right(data(photoTaken = 98_000)).text)
    }

    @Test
    fun `clock has two-digit minutes`() {
        assertEquals("00:00", HudTopRow.clock(0))
        assertEquals("59:59", HudTopRow.clock(3_599_000))
        assertEquals("1:00:05", HudTopRow.clock(3_605_000))
    }
}
