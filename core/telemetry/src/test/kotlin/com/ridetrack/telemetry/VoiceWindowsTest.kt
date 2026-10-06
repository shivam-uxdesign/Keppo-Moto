package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.VoiceWindows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceWindowsTest {
    @Test
    fun `one second at 44_1 kHz makes 31 windows of 32 ms, each with its context`() {
        val w = VoiceWindows(44_100)
        val out = ArrayList<FloatArray>()
        // Fed in uneven chunks, as the mic delivers them.
        var fed = 0
        while (fed < 44_100) {
            val n = minOf(2_048, 44_100 - fed)
            w.feed(n, { 0.0 }) { out += it }
            fed += n
        }
        assertEquals(16_000 / 512, out.size)
        assertTrue(out.all { it.size == 576 })
    }

    @Test
    fun `a 1 kHz tone keeps its level, and each window's context is the previous window's end`() {
        val w = VoiceWindows(48_000)
        val out = ArrayList<FloatArray>()
        w.feed(48_000, { i -> 0.5 * sin(2 * PI * 1_000 * i / 48_000) }) { out += it }
        val last = out.last()
        val peak = last.drop(64).maxOf { abs(it) }
        assertTrue(peak in 0.45f..0.55f, "peak $peak")
        val prev = out[out.size - 2]
        assertTrue((0 until 64).all { last[it] == prev[512 + it] })
    }
}
