package com.ridetrack.telemetry.moments

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A two-transmitter receiver (DJI Mic Mini in its mono setting) sends one mic on the left
 * channel and the other on the right. This splits a stereo chunk into the two, and tells
 * whether they really are two different mics. A single mic can arrive on both sides at any
 * volume: the same on both (the receiver's Mono, mixing the transmitters) or a quieter backup
 * copy (its Safety Track, -6 dB); either way one side is the other scaled, so it's measured
 * independent of volume. (Comparing the sides sample by sample took a -6 dB copy, or one side
 * saturating in wind, for two mics: 22 Oct, and the two-mic voice rules then heard "talking".)
 */
class TwoMics {
    private var cross = 0.0
    private var leftSq = 0.0
    private var rightSq = 0.0
    private var heardMs = 0L
    /** Two different mics were heard once: they stay two for the rest of the session (mics don't move). */
    private var everDiffer = false
    /** Sound heard in a row with both sides the same. */
    private var sameMs = 0L

    /**
     * Splits [frames] interleaved 16-bit stereo frames of [pcm] into [left] and [right], and
     * learns from them whether the channels differ. Returns (left dBFS, right dBFS).
     */
    fun split(pcm: ShortArray, frames: Int, left: ShortArray, right: ShortArray, sampleRate: Int): Pair<Float, Float> {
        var l2 = 0.0
        var r2 = 0.0
        var lr = 0.0
        for (i in 0 until frames) {
            val l = pcm[2 * i]
            val r = pcm[2 * i + 1]
            left[i] = l
            right[i] = r
            l2 += l.toDouble() * l
            r2 += r.toDouble() * r
            lr += l.toDouble() * r
        }
        // Only sound counts: silence on both sides says nothing about how many mics there are.
        if (frames > 0 && maxOf(l2, r2) / frames > SILENCE) {
            cross = cross * DECAY + lr
            leftSq = leftSq * DECAY + l2
            rightSq = rightSq * DECAY + r2
            val ms = frames * 1000L / sampleRate
            heardMs += ms
            if (heardMs >= MIN_HEARD_MS) {
                if (independence(cross, leftSq, rightSq) > THRESHOLD) { everDiffer = true; sameMs = 0 } else sameMs += ms
            }
        }
        return db(l2, frames) to db(r2, frames)
    }

    /**
     * Two different mics: the sides differed once enough sound had been heard. Kept for the
     * session: a moment of both mics hearing the same (wind, a horn) doesn't turn them into one.
     */
    val differ: Boolean get() = everDiffer

    /**
     * Both sides have carried the same mic for [MONO_MS] of sound and never differed: the
     * receiver is mixing both transmitters (Mono) or copying one (Safety Track). Not decided
     * from a moment.
     */
    val mono: Boolean get() = !everDiffer && sameMs >= MONO_MS

    /** How independent the two sides sound now (0 = one mic copied, about 1 = two mics), for the trace. */
    val independenceNow: Double get() = independence(cross, leftSq, rightSq)

    /** Enough sound has been heard to tell. */
    val known: Boolean get() = heardMs >= MIN_HEARD_MS

    companion object {
        /**
         * Above this [independence] the sides are two mics. Measured: the 20 Oct helmet and jacket
         * mics 0.96–1.0; a -6 dB copy 0.00; a copy with one side saturating 0.01 (0.22 at worst).
         */
        const val THRESHOLD = 0.5

        /** 0 when one side is the other at any volume, about 1 for two independent mics. */
        fun independence(lr: Double, l2: Double, r2: Double): Double =
            if (l2 <= 0 || r2 <= 0) 1.0 else (1 - lr * lr / (l2 * r2)).coerceIn(0.0, 1.0)
        const val MIN_HEARD_MS = 1_500L
        const val MONO_MS = 10_000L
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
