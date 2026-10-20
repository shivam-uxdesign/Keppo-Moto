package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.HighBandLift
import com.ridetrack.telemetry.moments.SpeechGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighBandLiftTest {
    /** The rider's own marks of when they talked (s), on the 20 Oct ride. */
    private val talk = listOf(10 to 15, 24 to 60, 80 to 84, 108 to 123, 128 to 150, 158 to 179, 184 to 186, 193 to 206, 210 to 250, 252 to 259)

    private fun ride(): List<Triple<Long, Float, Float>> =
        javaClass.getResource("/ride-20oct-high-band.csv")!!.readText().lines()
            .filter { it.isNotBlank() && it[0].isDigit() }
            .map { l -> l.split(',').let { Triple(it[0].toLong(), it[1].toFloat(), it[2].toFloat()) } }

    @Test
    fun `at 16-100 km in wind, every stretch the rider talked starts filming, with few false starts`() {
        val lift = HighBandLift()
        // Only the new rule: the voice detector read 0.00 on this ride and loudness never crossed.
        val gate = SpeechGate()
        val starts = ArrayList<Long>()
        val speaking = ArrayList<Pair<Long, Boolean>>()
        ride().forEach { (ms, v, e) ->
            val was = gate.speaking
            val now = gate.onLevel(ms, aboveDb = -99f, chunkMillis = 50, marginDb = 16f, voice = 0f, highLiftDb = lift.onLevels(ms, v, e))
            if (now && !was) starts += ms
            speaking += ms to now
        }
        val caught = talk.count { (a, b) -> speaking.any { (ms, on) -> on && ms in a * 1000L until b * 1000L } }
        val falseStarts = starts.filter { s -> talk.none { (a, b) -> s in (a - 1) * 1000L..(b + 5) * 1000L } }
        assertEquals(talk.size, caught, "talking stretches that filmed")
        assertTrue(falseStarts.size <= 2, "false starts at $falseStarts")
        // Most of the talking time counts as talking.
        val inTalk = speaking.filter { (ms, _) -> talk.any { (a, b) -> ms in a * 1000L until b * 1000L } }
        assertTrue(inTalk.count { it.second } >= 0.75 * inTalk.size, "covered ${inTalk.count { it.second } * 100 / inTalk.size}%")
    }

    @Test
    fun `wind and engine reaching both mics alike don't count, whatever their level`() {
        val lift = HighBandLift()
        var t = 0L
        repeat(1_200) { i ->
            val wind = -30f + 10f * kotlin.math.sin(i / 20.0).toFloat()
            assertTrue(lift.onLevels(t, wind + 2f, wind) < HighBandLift.SPEECH_DB)
            t += 50
        }
    }

    @Test
    fun `a voice on the helmet mic alone lifts it, and a minute of talking doesn't become usual`() {
        val lift = HighBandLift()
        var t = 0L
        repeat(400) { lift.onLevels(t, -30f, -30f); t += 50 }
        // 70 s of talking with short gaps between words (more than the minute it remembers): +9 dB.
        val lifts = ArrayList<Float>()
        repeat(1_400) { i -> lifts += lift.onLevels(t, if (i % 10 < 8) -21f else -30f, -30f); t += 50 }
        assertTrue(lifts.takeLast(20).max() >= HighBandLift.SPEECH_DB, "last second ${lifts.takeLast(20)}")
    }
}
