package com.ridetrack.telemetry.moments

/**
 * The mic overloaded: in strong wind the helmet mic hits full scale and a shout can't get any
 * louder than the wind. Counts samples at (or within 1% of) full scale over the last few
 * seconds; overloaded when more than [fraction] of them are. Pure; fed one chunk at a time.
 */
class ClipWatch(private val windowMillis: Long = 3_000L, private val fraction: Double = 0.005) {
    private val recent = ArrayDeque<Triple<Long, Int, Int>>() // (time, clipped, samples)

    /** Counts the clipped samples in a chunk of 16-bit audio. */
    fun count(get: (Int) -> Short, n: Int): Int {
        var c = 0
        for (i in 0 until n) { val v = get(i).toInt(); if (v >= FULL || v <= -FULL) c++ }
        return c
    }

    fun onChunk(timeMillis: Long, clipped: Int, samples: Int): Boolean {
        recent.addLast(Triple(timeMillis, clipped, samples))
        while (recent.isNotEmpty() && timeMillis - recent.first().first > windowMillis) recent.removeFirst()
        return overloaded
    }

    val overloaded: Boolean
        get() {
            val total = recent.sumOf { it.third }
            if (total < MIN_SAMPLES) return false
            return recent.sumOf { it.second }.toDouble() / total > fraction
        }

    fun reset() = recent.clear()

    companion object {
        /** Within 1% of 16-bit full scale. */
        const val FULL = 32_440
        /** About a second of sound before it says anything. */
        private const val MIN_SAMPLES = 44_100
    }
}
