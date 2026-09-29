package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.RollingBuffer
import kotlin.test.Test
import kotlin.test.assertTrue

class RollingBufferTest {
    /** 30 fps video with a keyframe every second, and ~43 audio frames/s. */
    private fun fill(b: RollingBuffer, seconds: Int) {
        var a = 0L
        for (f in 0 until seconds * 30) {
            val t = f * 33_333L
            b.addVideo(EncodedSample(t, f % 30 == 0, ByteArray(10)))
            while (a <= t) {
                b.addAudio(EncodedSample(a, true, ByteArray(2)))
                a += 23_220L
            }
        }
    }

    @Test
    fun `keeps only the capacity, trimmed in whole GOPs`() {
        val b = RollingBuffer(capacityMicros = 10_000_000)
        fill(b, 30)
        val (v, a) = b.extract(0, Long.MAX_VALUE)
        assertTrue(v.first().keyFrame)
        val span = v.last().wallMicros - v.first().wallMicros
        assertTrue(span in 10_000_000..11_000_000, "span $span")
        assertTrue(a.first().wallMicros >= v.first().wallMicros)
    }

    @Test
    fun `extraction starts on the keyframe at or before the window`() {
        val b = RollingBuffer(capacityMicros = 45_000_000)
        fill(b, 30)
        val (v, a) = b.extract(12_500_000, 20_000_000)
        assertTrue(v.first().keyFrame)
        assertTrue(v.first().wallMicros in 11_900_000..12_500_000, "start ${v.first().wallMicros}")
        assertTrue(v.last().wallMicros <= 20_000_000)
        assertTrue(a.isNotEmpty() && a.last().wallMicros <= 20_000_000)
    }

    @Test
    fun `interleave merges by time with video first on ties`() {
        val v = listOf(0L, 33L, 66L).map { EncodedSample(it, it == 0L, ByteArray(1)) }
        val a = listOf(0L, 23L, 46L, 69L).map { EncodedSample(it, true, ByteArray(1)) }
        val merged = RollingBuffer.interleave(v, a)
        assertTrue(merged.size == 7)
        assertTrue(merged.zipWithNext().all { (x, y) -> x.second.wallMicros <= y.second.wallMicros })
        assertTrue(merged[0].first && !merged[1].first) // tie at 0: video first
        assertTrue(merged.count { it.first } == 3)
    }

    @Test
    fun `log lines carry UTC time and stay on one line`() {
        val line = com.ridetrack.telemetry.moments.momentLogLine(1_759_118_321_342L, "clip skipped:\nno frames")
        assertTrue(line.startsWith("03:58:41.342Z  "), line)
        assertTrue(!line.contains('\n'))
    }

    @Test
    fun `window before the buffer starts at the first keyframe, empty buffer gives nothing`() {
        val b = RollingBuffer(capacityMicros = 5_000_000)
        assertTrue(b.extract(0, 10).first.isEmpty())
        fill(b, 20)
        val (v, _) = b.extract(0, 30_000_000)
        assertTrue(v.first().keyFrame && v.first().wallMicros >= 13_900_000)
    }
}
