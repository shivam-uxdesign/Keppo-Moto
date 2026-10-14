package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.BackgroundLevel
import com.ridetrack.telemetry.moments.SpeechGate
import com.ridetrack.telemetry.moments.TwoMicLift
import com.ridetrack.telemetry.moments.VoiceInWind
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
    fun `talking for 1 s starts it, and short gaps between words are fine`() {
        val g = SpeechGate()
        // Words of 300 ms with 150 ms gaps, from 1 s.
        val words = { t: Long -> if (t >= 1_000 && (t - 1_000) % 450 < 300) 14f else 0f }
        assertFalse(g.feed(0, 1_850, above = words))
        assertTrue(g.feed(1_850, 3_000, above = words))
        assertTrue(g.sustainedMillis in 1_000..1_500)
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

    @Test
    fun `in wind a shout counts though it's only a little above the background, wind alone doesn't`() {
        val windy = VoiceInWind.wind(-24f)
        assertEquals(1f, windy)
        assertEquals(0f, VoiceInWind.wind(-60f))
        val margin = VoiceInWind.margin(9f, windy)
        assertTrue(margin in 3f..3.5f)
        // A shout 5 dB over the wind, voice-like enough for wind (0.4): speaking.
        val shout = SpeechGate()
        var t = 0L
        while (t < 2_000) { shout.onLevel(t, 5f, 46, margin, voice = 0.4f, voiceMin = VoiceInWind.voiceMin(windy)); t += 46 }
        assertTrue(shout.speaking)
        // Calm settings would have missed it.
        val calm = SpeechGate()
        t = 0L
        while (t < 2_000) { calm.onLevel(t, 5f, 46, 9f, voice = 0.4f); t += 46 }
        assertFalse(calm.speaking)
        // Wind gusts, as loud, not voice-like: never.
        val gusts = SpeechGate()
        t = 0L
        while (t < 4_000) { gusts.onLevel(t, 6f, 46, margin, voice = 0.1f, voiceMin = VoiceInWind.voiceMin(windy)); t += 46 }
        assertFalse(gusts.speaking)
    }

    @Test
    fun `with two mics the helmet mic standing out is a voice, wind on both is not`() {
        val lift = TwoMicLift()
        var t = 0L
        // Riding: wind on both mics, the helmet mic usually 4 dB under the engine mic.
        while (t < 4_000) { lift.onLevels(t, -24f, -20f); t += 46 }
        // A gust on both: no lift.
        assertTrue(lift.onLevels(t, -14f, -10f) < 1f)
        // Talking: the helmet mic up 10 dB, the engine mic as before.
        t += 46
        assertTrue(lift.onLevels(t, -14f, -20f) >= VoiceInWind.LIFT_DB)
        // That counts as loud even with nothing above the background.
        val g = SpeechGate()
        t = 0L
        while (t < 2_000) { g.onLevel(t, 0f, 46, 9f, voice = 0.6f, liftDb = 9f); t += 46 }
        assertTrue(g.speaking)
    }

    @Test
    fun `with two mics, the helmet mic standing out counts even when the voice detector gives up in wind`() {
        val g = SpeechGate()
        var t = 0L
        // Only 1 dB over the wind and a voice score of 0.1, but the helmet mic is 8 dB louder than usual.
        while (t < 2_000) { g.onLevel(t, 1f, 46, 6f, voice = 0.1f, voiceMin = 0.35f, liftDb = 8f); t += 46 }
        kotlin.test.assertTrue(g.speaking)
        val noLift = SpeechGate()
        t = 0
        while (t < 2_000) { noLift.onLevel(t, 1f, 46, 6f, voice = 0.1f, voiceMin = 0.35f, liftDb = 1f); t += 46 }
        kotlin.test.assertFalse(noLift.speaking)
    }

    @Test
    fun `a mic overloaded by wind is noticed, an ordinary loud chunk isn't`() {
        val w = com.ridetrack.telemetry.moments.ClipWatch()
        val loud = ShortArray(2048) { if (it % 50 == 0) 32_767 else 12_000 }
        val ok = ShortArray(2048) { 20_000 }
        var t = 0L
        repeat(40) { w.onChunk(t, w.count({ ok[it] }, ok.size), ok.size); t += 46 }
        kotlin.test.assertFalse(w.overloaded)
        repeat(40) { w.onChunk(t, w.count({ loud[it] }, loud.size), loud.size); t += 46 }
        kotlin.test.assertTrue(w.overloaded)
    }
}
