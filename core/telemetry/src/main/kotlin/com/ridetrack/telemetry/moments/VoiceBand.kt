package com.ridetrack.telemetry.moments

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Loudness in the voice range only: a 300 Hz high-pass and a 3.4 kHz low-pass (2nd-order
 * Butterworth each). Wind roar and engine rumble sit mostly below 500 Hz, so most of it is
 * gone before the level is measured. Keeps its state between chunks; one per audio stream.
 */
class VoiceBand(sampleRate: Int, lowHz: Double = 300.0, highHz: Double = 3_400.0) {
    private val hp = Biquad.highPass(sampleRate, lowHz)
    private val lp = Biquad.lowPass(sampleRate, highHz)

    fun filter(x: Double): Double = lp.step(hp.step(x))

    /** RMS level of [samples] (−1..1) after the band, in dBFS. */
    fun levelDb(count: Int, sample: (Int) -> Double): Float {
        if (count <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until count) {
            val y = filter(sample(i))
            sum += y * y
        }
        return (10 * log10(sum / count + 1e-12)).toFloat().coerceAtLeast(SILENCE_DB)
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        companion object {
            private val Q = 1 / sqrt(2.0)

            // Robert Bristow-Johnson's audio EQ cookbook.
            fun highPass(fs: Int, f: Double): Biquad {
                val w = 2 * PI * f / fs
                val c = cos(w)
                val alpha = sin(w) / (2 * Q)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun lowPass(fs: Int, f: Double): Biquad {
                val w = 2 * PI * f / fs
                val c = cos(w)
                val alpha = sin(w) / (2 * Q)
                val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        const val SILENCE_DB = -90f
    }
}

/**
 * The background noise right now (wind at this speed, engine, traffic): a low percentile of
 * the last few seconds of levels. It follows the wind as speed changes, but talking, with
 * its small gaps between words, doesn't lift it.
 */
class BackgroundLevel(private val windowMillis: Long = 4_000L, private val percentile: Double = 0.1) {
    private val recent = ArrayDeque<Pair<Long, Float>>()

    /** Adds one chunk's level; returns the background (dBFS). */
    fun onLevel(timeMillis: Long, levelDb: Float): Float {
        recent.addLast(timeMillis to levelDb)
        while (recent.isNotEmpty() && timeMillis - recent.first().first > windowMillis) recent.removeFirst()
        return current
    }

    val current: Float
        get() {
            if (recent.isEmpty()) return VoiceBand.SILENCE_DB
            val sorted = recent.map { it.second }.sorted()
            return sorted[((sorted.size - 1) * percentile).toInt()]
        }

    fun reset() = recent.clear()
}
