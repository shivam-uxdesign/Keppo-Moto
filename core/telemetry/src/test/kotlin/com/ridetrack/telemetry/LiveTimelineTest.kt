package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.LiveTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTimelineTest {
    @Test
    fun `starts at the first keyframe`() {
        val t = LiveTimeline()
        assertNull(t.video(1_000, keyFrame = false))
        assertNull(t.audio(1_500))
        assertEquals(0L, t.video(2_000, keyFrame = true))
        assertEquals(500L, t.audio(2_500))
        assertEquals(1_000L, t.video(3_000, keyFrame = false))
    }

    @Test
    fun `pause cuts the gap out and resumes on a keyframe`() {
        val t = LiveTimeline()
        t.video(0, true)
        t.video(1_000_000, false)
        t.pause()
        assertTrue(t.paused)
        assertNull(t.video(2_000_000, true))
        assertNull(t.audio(2_000_000))
        t.resume()
        // Still waiting for a keyframe after the resume.
        assertNull(t.video(5_000_000, false))
        assertNull(t.audio(5_000_000))
        // Keyframe at 6 s: paused from 1 s to 6 s, so it lands just after the 1 s frame.
        assertEquals(1_000_001L, t.video(6_000_000, true))
        assertEquals(1_033_000L, t.video(6_033_000, false))
        assertEquals(1_010_000L, t.audio(6_010_000))
        assertEquals(1_033_000L, t.lengthMicros)
    }

    @Test
    fun `timestamps never go backwards`() {
        val t = LiveTimeline()
        t.video(1_000, true)
        assertEquals(1L, t.video(900, false))
        t.audio(2_000)
        assertEquals(1_001L, t.audio(1_500))
    }
}
