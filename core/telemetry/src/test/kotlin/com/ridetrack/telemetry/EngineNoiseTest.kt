package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.EngineNoise
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineNoiseTest {
    private val rate = 8_000

    /** An engine: a 50 Hz firing pitch with harmonics and some road noise. */
    private fun engine(seconds: Double, seed: Int = 1): DoubleArray {
        val r = Random(seed)
        return DoubleArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            (1..12).sumOf { h -> 0.05 / h * sin(2 * PI * 50 * h * t) } + (r.nextDouble() - 0.5) * 0.02
        }
    }

    private fun pcm(x: DoubleArray) = ShortArray(x.size) { (x[it].coerceIn(-1.0, 1.0) * 32000).toInt().toShort() }

    @Test fun `a two-tone horn over the engine is found, with its pitches`() {
        val x = engine(4.0)
        for (i in (1.5 * rate).toInt() until (2.3 * rate).toInt()) {
            val t = i.toDouble() / rate
            x[i] += 0.12 * sin(2 * PI * 420 * t) + 0.1 * sin(2 * PI * 500 * t)
        }
        val found = EngineNoise.find(pcm(x), rate) { 50f }
        val horn = found.single { it.kind == EngineNoise.Kind.HORN }
        assertTrue(horn.startMs in 1_400L..1_600L && horn.endMs in 2_200L..2_450L)
        assertTrue(horn.freqsHz.any { kotlin.math.abs(it - 420f) < 20f })
    }

    @Test fun `a bump is a short bang, and the engine alone is nothing`() {
        val x = engine(3.0)
        val r = Random(7)
        for (i in (1.0 * rate).toInt() until (1.08 * rate).toInt()) x[i] += (r.nextDouble() - 0.5) * 1.6 * (1 - (i - rate) / (0.08 * rate))
        val found = EngineNoise.find(pcm(x), rate) { 50f }
        assertEquals(EngineNoise.Kind.BUMP, found.single().kind)
        assertTrue(found.single().startMs in 900L..1_100L)
        assertTrue(EngineNoise.find(pcm(engine(3.0, seed = 3)), rate) { 50f }.isEmpty())
    }
}
