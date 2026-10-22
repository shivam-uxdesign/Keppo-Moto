package com.ridetrack.telemetry.obd

import kotlin.math.abs

/**
 * The gear, worked out: bikes rarely report it, but RPM ÷ speed is fixed for each gear. While
 * riding steadily it collects that ratio and finds the bike's gears as the clusters it falls in
 * (a 6-speed has six); then each reading's ratio picks the nearest. Neutral (0) when the engine
 * revs and the bike barely moves, null while the clutch slips or it isn't sure. Learned ratios
 * are kept per bike ([ratios]) so the next ride knows them at once. Pure.
 */
class GearLearner(learned: List<Double> = emptyList(), private val gears: Int = 6) {
    private val seen = ArrayList<Double>()
    private var steady: Pair<Double, Int>? = null // ratio, readings in a row near it

    /** The gears' RPM per km/h, 1st (highest) first. */
    var ratios: List<Double> = learned.sortedDescending()
        private set

    /** One reading: RPM and road speed (km/h). Returns the gear, 0 for neutral, null if unsure. */
    fun onReading(rpm: Double?, speedKmh: Double?): Int? {
        if (rpm == null || speedKmh == null) return null
        if (rpm >= NEUTRAL_RPM && speedKmh < 3) return 0
        if (speedKmh < MIN_SPEED || rpm < 600) return null
        val ratio = rpm / speedKmh
        // Only steady stretches teach it (a slipping clutch or a shift in progress doesn't).
        val s = steady
        steady = if (s != null && abs(ratio - s.first) / s.first < 0.04) (s.first * 0.8 + ratio * 0.2) to s.second + 1 else ratio to 1
        if ((steady?.second ?: 0) >= STEADY_READINGS) {
            seen += steady!!.first
            if (seen.size > MAX_SEEN) seen.removeAt(0)
            if (seen.size >= MIN_SEEN && seen.size % 20 == 0) ratios = cluster(seen, gears)
        }
        return gearOf(ratio)
    }

    /** The gear whose ratio is within 8% of [ratio], or null. */
    fun gearOf(ratio: Double): Int? {
        val r = ratios
        if (r.isEmpty()) return null
        val best = r.withIndex().minByOrNull { abs(it.value - ratio) / it.value } ?: return null
        return if (abs(best.value - ratio) / best.value <= 0.08) best.index + 1 else null
    }

    companion object {
        const val NEUTRAL_RPM = 1_800.0
        const val MIN_SPEED = 8.0
        const val STEADY_READINGS = 5
        const val MIN_SEEN = 60
        const val MAX_SEEN = 2_000

        /**
         * Groups ratios into up to [k] gears: sorted, then split at the biggest relative gaps (gears
         * sit 15–40% apart; within a gear the ratio varies a few %). Groups seen too rarely to trust
         * are dropped. Returns each group's median, highest first.
         */
        fun cluster(values: List<Double>, k: Int): List<Double> {
            if (values.size < 10) return emptyList()
            val s = values.sorted()
            val gaps = (1 until s.size).map { i -> i to (s[i] - s[i - 1]) / s[i - 1] }
                .filter { it.second > 0.10 }
                .sortedByDescending { it.second }
                .take(k - 1)
                .map { it.first }
                .sorted()
            val groups = ArrayList<List<Double>>()
            var from = 0
            for (cut in gaps + s.size) {
                groups += s.subList(from, cut)
                from = cut
            }
            return groups.filter { it.size >= maxOf(5, values.size / 50) }.map { it[it.size / 2] }.sortedDescending()
        }
    }
}
