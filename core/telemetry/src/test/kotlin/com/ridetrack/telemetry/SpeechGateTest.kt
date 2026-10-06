package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.BackgroundLevel
import com.ridetrack.telemetry.moments.SpeechGate
import com.ridetrack.telemetry.moments.VoiceBand
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechGateTest {
    /** 46 ms chunks, like 2048 samples at 44.1 kHz; [above] is dB over the background. */
    private fun SpeechGate.feed(from: Long, to: Long, margin: Float = 9f, above: (Long) -> Float): Boolean {
        var any = false
        var t = from
        while (t < to) {
            any = onLevel(t, above(t), 46, margin) || any
            t += 46
        }
        return any
    }

    @Test
    fun `a breath, a cough or a horn tap is too short`() {
        val g = SpeechGate()
        assertFalse(g.feed(0, 3_000) { t -> if (t in 500..1_300) 20f else 0f }) // 0.8 s
        assertNull(g.lastSpeechMillis)
    }

    @Test
    fun `breathing, on and off, never counts`() {
        val g = SpeechGate()
        // 0.6 s breath in, 0.6 s quiet, repeated.
        assertFalse(g.feed(0, 10_000) { t -> if ((t / 600) % 2 == 0L) 15f else 0f })
    }

    @Test
    fun `talking for 1_5 s starts it, and short gaps between words are fine`() {
        val g = SpeechGate()
        // Words of 300 ms with 150 ms gaps, from 1 s.
        val words = { t: Long -> if (t >= 1_000 && (t - 1_000) % 450 < 300) 14f else 0f }
        assertFalse(g.feed(0, 2_300, above = words))
        assertTrue(g.feed(2_300, 4_000, above = words))
        assertTrue(g.sustainedMillis in 1_500..2_000)
    }

    @Test
    fun `once speaking, slow talk keeps it going, silence ends it`() {
        val g = SpeechGate()
        g.feed(0, 2_000) { 14f }
        assertTrue(g.speaking)
        // Slower: 300 ms words, 600 ms gaps.
        g.feed(2_000, 5_000) { t -> if ((t - 2_000) % 900 < 300) 14f else 0f }
        assertTrue(g.speaking)
        val last = g.lastSpeechMillis!!
        g.feed(5_000, 7_000) { 0f }
        assertFalse(g.speaking)
        assertEquals(last, g.lastSpeechMillis)
    }

    @Test
    fun `a horn or an engine as loud and long as talking doesn't count, a voice does`() {
        var t = 0L
        val horn = SpeechGate()
        while (t < 4_000) { horn.onLevel(t, 20f, 46, 16f, voice = 0.02f); t += 46 }
        assertFalse(horn.speaking)
        val talk = SpeechGate()
        t = 0L
        while (t < 4_000) { talk.onLevel(t, 20f, 46, 16f, voice = 0.9f); t += 46 }
        assertTrue(talk.speaking)
    }

    @Test
    fun `the margin decides`() {
        assertFalse(SpeechGate().feed(0, 3_000, margin = 12f) { 10f })
        assertTrue(SpeechGate().feed(0, 3_000, margin = 9f) { 10f })
    }

    @Test
    fun `background follows the wind, but talking doesn't lift it`() {
        val b = BackgroundLevel()
        var t = 0L
        fun run(ms: Long, db: (Long) -> Float): Float {
            var bg = 0f
            val end = t + ms
            while (t < end) { bg = b.onLevel(t, db(t)); t += 46 }
            return bg
        }
        assertEquals(-40f, run(5_000) { -40f })
        // Faster: wind 10 dB louder; within ~4 s it is the background.
        assertEquals(-30f, run(5_000) { -30f })
        // Talking over it, with gaps between words.
        val bg = run(6_000) { tt -> if (tt % 450 < 300) -15f else -30f }
        assertEquals(-30f, bg)
    }

    @Test
    fun `the voice band takes out low rumble, keeps the voice`() {
        val fs = 44_100
        fun level(hz: Double): Float {
            val band = VoiceBand(fs)
            band.levelDb(fs / 2) { i -> 0.5 * sin(2 * PI * hz * i / fs) } // settle
            return band.levelDb(fs / 2) { i -> 0.5 * sin(2 * PI * hz * (i + fs / 2) / fs) }
        }
        val voice = level(1_000.0)
        assertTrue(voice > -10f, "1 kHz kept: $voice")
        assertTrue(level(80.0) < voice - 15f, "80 Hz rumble cut: ${level(80.0)}")
        assertTrue(level(8_000.0) < voice - 10f, "8 kHz hiss cut: ${level(8_000.0)}")
    }
}
