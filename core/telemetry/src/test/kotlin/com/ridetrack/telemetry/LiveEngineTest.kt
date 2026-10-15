package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.LiveTimeline
import com.ridetrack.telemetry.moments.RollingBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live video streams the engine mic too, from its look-back on, in step with the voice. */
class LiveEngineTest {
    private fun feed(b: RollingBuffer, fromSec: Int, toSec: Int) {
        for (f in fromSec * 30 until toSec * 30) {
            val t = f * 33_333L
            b.addVideo(EncodedSample(t, f % 30 == 0, ByteArray(10)))
            b.addAudio(EncodedSample(t, true, ByteArray(2)))
            b.addExtra(EncodedSample(t, true, ByteArray(2)))
        }
    }

    @Test
    fun engineMicFollowsTheLookBackAndTheLiveStream() {
        val b = RollingBuffer()
        feed(b, 0, 20)
        val timeline = LiveTimeline()
        val voice = mutableListOf<Long>()
        val engine = mutableListOf<Long>()
        // A voice video from 10 s back.
        val ok = b.startTap(
            10_000_000L,
            seed = { v, a -> RollingBuffer.interleave(v, a).forEach { (isV, s) -> if (isV) timeline.video(s.wallMicros, s.keyFrame) else timeline.audio(s.wallMicros)?.let(voice::add) } },
            onSample = { isV, s -> if (isV) timeline.video(s.wallMicros, s.keyFrame) else timeline.audio(s.wallMicros)?.let(voice::add) },
            onExtra = { s -> timeline.extra(s.wallMicros)?.let(engine::add) },
        )
        assertTrue(ok)
        feed(b, 20, 30)
        b.stopTap()
        feed(b, 30, 32)
        // Same stretch on both mics: from the look-back to the stop, nothing after.
        assertEquals(voice.size, engine.size)
        assertEquals(voice.first(), engine.first())
        assertEquals(voice.last(), engine.last())
        assertTrue(engine.last() in 19_900_000L..20_000_000L)
    }

    @Test
    fun engineMicPausesWithTheVideo() {
        val t = LiveTimeline()
        t.video(0, true)
        assertEquals(0L, t.extra(0))
        t.video(1_000_000, false)
        t.pause()
        assertNull(t.extra(1_500_000))
        t.resume()
        t.video(3_000_000, true)
        // After the pause, the engine mic carries on where the video does (the 2 s gap is cut).
        assertEquals(1_000_000L, t.extra(3_000_000))
    }
}
