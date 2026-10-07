package com.ridetrack.telemetry.moments

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A two-transmitter receiver (DJI Mic Mini in its mono setting) sends one mic on the left
 * channel and the other on the right. This splits a stereo chunk into the two, and tells
 * whether they really are two different mics (a single mic arrives the same on both sides).
 */
class TwoMics {
    private var diff = 0.0
    private var total = 0.0
    private var heardMs = 0L

    /**
     * Splits [frames] interleaved 16-bit stereo frames of [pcm] into [left] and [right], and
     * learns from them whether the channels differ. Returns (left dBFS, right dBFS).
     */
    fun split(pcm: ShortArray, frames: Int, left: ShortArray, right: ShortArray, sampleRate: Int): Pair<Float, Float> {
        var l2 = 0.0
        var r2 = 0.0
        var d2 = 0.0
        for (i in 0 until frames) {
            val l = pcm[2 * i]
            val r = pcm[2 * i + 1]
            left[i] = l
            right[i] = r
            l2 += l.toDouble() * l
            r2 += r.toDouble() * r
            val d = l.toDouble() - r
            d2 += d * d
        }
        // Only sound counts: silence on both sides says nothing about how many mics there are.
        if (frames > 0 && maxOf(l2, r2) / frames > SILENCE) {
            diff = diff * DECAY + d2
            total = total * DECAY + (l2 + r2)
            heardMs += frames * 1000L / sampleRate
        }
        return db(l2, frames) to db(r2, frames)
    }

    /** Two different mics: the sides differ, once enough sound has been heard to tell. */
    val differ: Boolean get() = heardMs >= MIN_HEARD_MS && total > 0 && diff / total > THRESHOLD

    /** Enough sound has been heard to tell. */
    val known: Boolean get() = heardMs >= MIN_HEARD_MS

    companion object {
        /** Difference energy over both sides' energy: identical copies give 0, independent mics about 1. */
        const val THRESHOLD = 0.15
        const val MIN_HEARD_MS = 1_500L
        private const val DECAY = 0.98
        /** Mean square below this (about -60 dBFS) is silence. */
        private const val SILENCE = 32768.0 * 32768.0 * 1e-6

        fun db(sumSquares: Double, n: Int): Float {
            if (n <= 0) return -90f
            val rms = sqrt(sumSquares / n) / 32768.0
            return if (rms <= 1e-5) -90f else (20 * log10(rms)).toFloat()
        }
    }
}
