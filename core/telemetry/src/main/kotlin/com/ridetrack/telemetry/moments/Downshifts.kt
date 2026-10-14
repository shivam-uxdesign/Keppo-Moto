package com.ridetrack.telemetry.moments

/**
 * Downshifts in a clip, from its engine sound and the ride's speed: while slowing down, the
 * engine's pitch jumps up (the lower gear spins it faster, or a blip to match revs) and stays
 * up. Accelerating in the same gear raises the pitch too, but never while slowing, so braking is
 * what tells them apart. With OBD the gear itself will say; this works without it. Pure.
 */
object Downshifts {
    /** The pitch must rise at least this much (a gear is about 15–40% apart). */
    const val MIN_RISE = 1.15f
    /** More than this is a misread pitch (an octave jump), not a gear. */
    const val MAX_RISE = 2.2f
    /** Slowing: at least this much speed lost (m/s) between 1 s before and 1 s after. */
    const val MIN_SLOWING_MPS = 1.0
    /** Only while actually riding (m/s, about 11 km/h). */
    const val MIN_SPEED_MPS = 3.0
    /** One downshift per this long (a double blip is one shift). */
    const val GAP_MS = 1_500L

    /**
     * Where the downshifts are, in ms into the clip. [pitches]: the engine's pitch every [hopMs]
     * (0 = unknown); [speedAt]: the ride's speed (m/s) [ms] into the clip, null where unknown.
     */
    fun find(pitches: FloatArray, hopMs: Long, speedAt: (Long) -> Double?): List<Long> {
        if (hopMs <= 0) return emptyList()
        val before = (600 / hopMs).toInt().coerceAtLeast(2)
        val skip = (200 / hopMs).toInt().coerceAtLeast(1)
        val hold = (300 / hopMs).toInt().coerceAtLeast(2)
        val out = ArrayList<Long>()
        var next = 0L
        for (i in before until pitches.size - hold) {
            val t = i * hopMs
            if (t < next) continue
            val was = median(pitches, i - before, i - skip) ?: continue
            val now = median(pitches, i, i + hold) ?: continue
            if (now < was * MIN_RISE || now > was * MAX_RISE) continue
            val s0 = speedAt(t - 1_000) ?: continue
            val s1 = speedAt(t + 1_000) ?: continue
            if (s0 < MIN_SPEED_MPS || s0 - s1 < MIN_SLOWING_MPS) continue
            out += t
            next = t + GAP_MS
        }
        return out
    }

    /** The median of the known pitches in [from, to); null with fewer than two. */
    private fun median(p: FloatArray, from: Int, to: Int): Float? {
        val known = (from.coerceAtLeast(0) until to.coerceAtMost(p.size)).map { p[it] }.filter { it > 0f }.sorted()
        return if (known.size < 2) null else known[known.size / 2]
    }
}
