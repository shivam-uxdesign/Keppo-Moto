package com.ridetrack.telemetry.moments

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the mic's samples (any rate) into what the Silero voice detector reads: 16 kHz
 * windows of [WINDOW] new samples, each with the [CONTEXT] samples before it in front.
 * Low-passes at 7 kHz first so nothing above 8 kHz folds back. Pure; one per stream.
 */
class VoiceWindows(private val inputRate: Int) {
    private val step = inputRate.toDouble() / RATE
    private val lp = LowPass(inputRate, 7_000.0)
    private var pos = 0.0 // next output sample's position, in input samples since the last one kept
    private var prev = 0.0
    private val pending = FloatArray(WINDOW)
    private var filled = 0
    private val context = FloatArray(CONTEXT)

    /** Feeds [count] samples (−1..1); calls [onWindow] with each complete window (length CONTEXT + WINDOW). */
    fun feed(count: Int, sample: (Int) -> Double, onWindow: (FloatArray) -> Unit) {
        for (i in 0 until count) {
            val x = lp.step(sample(i))
            // Output samples falling between the previous input sample and this one.
            while (pos <= 1.0) {
                push((prev + (x - prev) * pos).toFloat(), onWindow)
                pos += step
            }
            pos -= 1.0
            prev = x
        }
    }

    private fun push(v: Float, onWindow: (FloatArray) -> Unit) {
        pending[filled++] = v
        if (filled < WINDOW) return
        val window = FloatArray(CONTEXT + WINDOW)
        context.copyInto(window, 0)
        pending.copyInto(window, CONTEXT)
        pending.copyInto(context, 0, WINDOW - CONTEXT, WINDOW)
        filled = 0
        onWindow(window)
    }

    private class LowPass(fs: Int, f: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w = 2 * PI * f / fs
            val c = cos(w)
            val alpha = sin(w) / (2 / sqrt(2.0))
            val a0 = 1 + alpha
            b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        }

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    companion object {
        const val RATE = 16_000
        const val WINDOW = 512
        const val CONTEXT = 64
    }
}
