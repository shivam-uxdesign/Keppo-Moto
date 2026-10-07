package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.TwoMics
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoMicsTest {
    private val rate = 44_100

    private fun feed(m: TwoMics, seconds: Int, l: (Int) -> Double, r: (Int) -> Double) {
        val frames = 2048
        val pcm = ShortArray(frames * 2)
        val left = ShortArray(frames)
        val right = ShortArray(frames)
        var t = 0
        repeat(seconds * rate / frames) {
            for (i in 0 until frames) {
                pcm[2 * i] = (l(t + i) * 12_000).toInt().toShort()
                pcm[2 * i + 1] = (r(t + i) * 12_000).toInt().toShort()
            }
            m.split(pcm, frames, left, right, rate)
            t += frames
        }
    }

    @Test
    fun `one mic on both sides is not two mics`() {
        val m = TwoMics()
        feed(m, 3, { sin(2 * PI * 220 * it / rate) }, { sin(2 * PI * 220 * it / rate) })
        assertTrue(m.known)
        assertFalse(m.differ)
    }

    @Test
    fun `a voice on the left and an engine on the right are two mics`() {
        val m = TwoMics()
        feed(m, 3, { sin(2 * PI * 300 * it / rate) }, { 0.6 * sin(2 * PI * 80 * it / rate) })
        assertTrue(m.differ)
    }

    @Test
    fun `silence tells nothing`() {
        val m = TwoMics()
        feed(m, 3, { 0.0 }, { 0.0 })
        assertFalse(m.known)
        assertFalse(m.differ)
    }

    @Test
    fun `split puts each side in its own track`() {
        val m = TwoMics()
        val pcm = shortArrayOf(1, 2, 3, 4, 5, 6)
        val l = ShortArray(3)
        val r = ShortArray(3)
        m.split(pcm, 3, l, r, rate)
        assertEquals(listOf<Short>(1, 3, 5), l.toList())
        assertEquals(listOf<Short>(2, 4, 6), r.toList())
    }
}
