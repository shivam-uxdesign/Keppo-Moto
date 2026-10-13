package com.ridetrack.app.studio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * How hard the engine is revving, worked out from its sound: the engine's pitch (its firing
 * rate) rises with the revs. Pure, so it's unit-tested; the decoding is in [EngineAudio].
 */
object Revs {
    /** One pitch reading every this long. */
    const val HOP_MS = 100L
    /** Pitches searched for (Hz): a single at idle to a twin near its limiter. */
    const val MIN_HZ = 20f
    const val MAX_HZ = 320f
    /** Above this level (0..1 of the bike's own range) the engine is at the redline. */
    const val REDLINE = 0.9f

    /**
     * The engine's pitch (Hz) every [HOP_MS] across [pcm] (mono, [rate] Hz); 0 where it can't
     * tell (silence, or no clear note).
     */
    fun pitches(pcm: ShortArray, rate: Int): FloatArray {
        val hop = (rate * HOP_MS / 1000).toInt().coerceAtLeast(1)
        // A window long enough to hold a few periods of the lowest pitch.
        val win = (rate * 4 / MIN_HZ).toInt().coerceAtMost(pcm.size)
        if (win < 32) return FloatArray(0)
        val n = ((pcm.size - win) / hop + 1).coerceAtLeast(0)
        val minLag = (rate / MAX_HZ).toInt().coerceAtLeast(2)
        val maxLag = (rate / MIN_HZ).toInt().coerceAtMost(win / 2)
        val out = FloatArray(n)
        val x = FloatArray(win)
        for (k in 0 until n) {
            val at = k * hop
            var mean = 0f
            for (i in 0 until win) { x[i] = pcm[at + i].toFloat(); mean += x[i] }
            mean /= win
            var energy = 0f
            for (i in 0 until win) { x[i] -= mean; energy += x[i] * x[i] }
            // Too quiet to hear an engine.
            if (sqrt(energy / win) < SILENCE) continue
            out[k] = pitchOf(x, minLag, maxLag, energy, rate)
        }
        return smooth(out)
    }

    /** The strongest repeating period (normalised autocorrelation), as a frequency; 0 when nothing repeats clearly. */
    private fun pitchOf(x: FloatArray, minLag: Int, maxLag: Int, energy: Float, rate: Int): Float {
        var best = 0f
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var s = 0f
            val m = x.size - lag
            for (i in 0 until m) s += x[i] * x[i + lag]
            val r = s / energy
            if (r > best) { best = r; bestLag = lag }
        }
        return if (best >= CLARITY && bestLag > 0) rate.toFloat() / bestLag else 0f
    }

    /** A median of three, so one odd reading doesn't flash the redline. */
    private fun smooth(a: FloatArray): FloatArray = FloatArray(a.size) { i ->
        val p = a[max(0, i - 1)]; val q = a[i]; val r = a[minOf(a.lastIndex, i + 1)]
        maxOf(minOf(p, q), minOf(maxOf(p, q), r))
    }

    /**
     * The bike's own range from all its readings (every clip of the ride): the low end (cruising)
     * and the top (near the limiter). Null when there's too little to go on.
     */
    fun range(all: List<FloatArray>): ClosedFloatingPointRange<Float>? {
        val v = all.flatMap { a -> a.filter { it > 0f } }.sorted()
        if (v.size < MIN_READINGS) return null
        val lo = v[(v.size * LOW_PCT).toInt()]
        val hi = v[((v.size - 1) * HIGH_PCT).toInt()]
        return if (hi > lo * MIN_SPREAD) lo..hi else null
    }

    /** How hard it's revving (0..1 of the bike's range) at [ms] into a clip with these [pitches]; null where unknown. */
    fun level(pitches: FloatArray, range: ClosedFloatingPointRange<Float>, ms: Long): Float? {
        val i = (ms / HOP_MS).toInt()
        val f = pitches.getOrNull(i)?.takeIf { it > 0f } ?: return null
        return ((f - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    }

    /**
     * The redline light at [tMs]: 0 when off, rising and falling in pulses that come faster as the
     * revs climb (about 2 a second at the line, 8 at the limiter). [levelAt] gives the rev level
     * (0..1) at a time, null where unknown; it's read back from [tMs] so the pulse runs on smoothly.
     */
    fun pulse(levelAt: (Long) -> Float?, tMs: Long, fromMs: Long = 0L): Float {
        val start = maxOf(fromMs, tMs - LOOK_BACK_MS)
        var phase = 0.0
        var env = 0f
        var t = start
        while (t <= tMs) {
            val l = levelAt(t) ?: 0f
            val on = l >= REDLINE
            env = if (on) minOf(1f, env + STEP_MS / RAMP_IN_MS) else maxOf(0f, env - STEP_MS / RAMP_OUT_MS)
            val hz = PULSE_MIN_HZ + (PULSE_MAX_HZ - PULSE_MIN_HZ) * ((l - REDLINE) / (1 - REDLINE)).coerceIn(0f, 1f)
            if (on || env > 0f) phase += 2 * Math.PI * hz * STEP_MS / 1000.0
            t += STEP_MS.toLong()
        }
        return env * (0.5f - 0.5f * kotlin.math.cos(phase).toFloat())
    }

    /** A rider's redline tag reads as climbing from the line to the limiter across it. */
    fun tagLevel(tag: RedlineTag, atMs: Long): Float? =
        if (atMs in tag.startMs until tag.endMs) REDLINE + (1 - REDLINE) * (atMs - tag.startMs).toFloat() / (tag.endMs - tag.startMs).coerceAtLeast(1) else null

    private const val STEP_MS = 33f
    private const val LOOK_BACK_MS = 4_000L
    private const val RAMP_IN_MS = 150f
    private const val RAMP_OUT_MS = 100f
    private const val PULSE_MIN_HZ = 2f
    private const val PULSE_MAX_HZ = 8f

    private const val SILENCE = 200f
    private const val CLARITY = 0.45f
    private const val MIN_READINGS = 30
    private const val LOW_PCT = 0.10f
    private const val HIGH_PCT = 0.98f
    private const val MIN_SPREAD = 1.3f
}
