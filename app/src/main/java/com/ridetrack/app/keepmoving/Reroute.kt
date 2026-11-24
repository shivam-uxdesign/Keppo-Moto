package com.ridetrack.app.keepmoving

/**
 * Keep moving, step 2: when to look for a clearer road during a ride, and when to switch to it.
 *
 * The road being ridden is scored from the Navigation SDK's own traffic on it (free, updated by
 * Google as you go), with [RouteScore]'s weights. Google's Routes API (a paid request) is asked
 * only when that road has trouble ahead, and at most every [MIN_ASK_GAP_MS]. A new road wins only
 * when it's clearly better ([BETTER_SHARE] and [MIN_GAIN]), so the route doesn't flip back and forth.
 */
object Reroute {
    /** Look this far ahead on the current road for trouble. */
    const val AHEAD_M = 5_000
    /** Trouble ahead worth a look: about 150 m of red, or 470 m of yellow. */
    const val LOOK_COST = 150.0
    /** At most one paid look this often. */
    const val MIN_ASK_GAP_MS = 90_000L
    /** A new road must cost at most this share of the current one... */
    const val BETTER_SHARE = 0.7
    /** ...and save at least this much (about 150 m of red). */
    const val MIN_GAIN = 150.0
    /** After a switch, no other for this long. */
    const val MIN_SWITCH_GAP_MS = 3 * 60_000L
    /** After "Keep this road", no offers for this long. */
    const val KEEP_FOR_MS = 5 * 60_000L
    /** "Keep this road" can be tapped for this long before the switch happens. */
    const val COUNTDOWN_MS = 10_000L
    /** Two lines are the same road when this share of the new one is this close to the old. */
    const val SAME_SHARE = 0.8
    const val SAME_WITHIN_M = 60.0

    /** One stretch of the current road from the Navigation SDK: [offsetM] from its start, [speed]. */
    data class Stretch(val offsetM: Int, val lengthM: Int, val speed: Speed)

    /** The current road's metres by colour (all of it, or the first [aheadM]); the rest is clear. */
    fun colours(stretches: List<Stretch>, totalM: Int, aheadM: Int = Int.MAX_VALUE): Colours {
        val end = minOf(totalM, aheadM)
        fun within(s: Stretch) = (minOf(s.offsetM + s.lengthM, end) - s.offsetM).coerceAtLeast(0)
        val slow = stretches.filter { it.speed == Speed.SLOW }.sumOf(::within)
        val jam = stretches.filter { it.speed == Speed.JAM }.sumOf(::within)
        val unknown = stretches.filter { it.speed == Speed.UNKNOWN }.sumOf(::within)
        return Colours((end - slow - jam - unknown).coerceAtLeast(0), slow, jam, unknown)
    }

    /** Trouble ahead on the current road, and the last look long enough ago: ask Google. */
    fun shouldLook(stretches: List<Stretch>, totalM: Int, nowMs: Long, lastAskMs: Long, keptUntilMs: Long, lastSwitchMs: Long): Boolean =
        nowMs - lastAskMs >= MIN_ASK_GAP_MS && nowMs >= keptUntilMs && nowMs - lastSwitchMs >= MIN_SWITCH_GAP_MS &&
            RouteScore.cost(colours(stretches, totalM, AHEAD_M)).let { it - clearPart(stretches, totalM) >= LOOK_COST }

    /** The clear road's own small cost in the first [AHEAD_M], left out of "trouble". */
    private fun clearPart(stretches: List<Stretch>, totalM: Int) = colours(stretches, totalM, AHEAD_M).clearM * RouteScore.CLEAR_PER_M

    /**
     * The road to switch to, or null to stay: the clearest of Google's [ranked] routes, when it
     * isn't the road already ridden ([current] line) and is clearly better than its [currentCost].
     */
    fun pick(ranked: List<Ranked>, current: List<LatLon>, currentCost: Double): Ranked? {
        val best = ranked.firstOrNull { !it.tooLong } ?: return null
        if (best.route.routeToken == null) return null
        if (sameRoad(current, TrafficRoutes.decodePolyline(best.route.polyline))) return null
        val better = best.cost <= currentCost * BETTER_SHARE && currentCost - best.cost >= MIN_GAIN
        return best.takeIf { better }
    }

    /** [next]'s first 3 km lies along [current]: it's the road already being ridden. */
    fun sameRoad(current: List<LatLon>, next: List<LatLon>, firstM: Double = 3_000.0): Boolean {
        if (current.size < 2 || next.size < 2) return false
        val samples = ArrayList<LatLon>()
        var run = 0.0
        samples += next.first()
        for (k in 1 until next.size) {
            run += TrafficRoutes.distanceM(next[k - 1], next[k])
            if (run > firstM) break
            samples += next[k]
        }
        val near = samples.count { p -> distanceToLineM(p, current) <= SAME_WITHIN_M }
        return near >= samples.size * SAME_SHARE
    }

    /** Metres from [p] to the nearest point of [line] (flat-earth, fine at city scale). */
    fun distanceToLineM(p: LatLon, line: List<LatLon>): Double {
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * kotlin.math.cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        for (k in 0 until line.size - 1) {
            val ax = (line[k].lon - p.lon) * mPerLon
            val ay = (line[k].lat - p.lat) * mPerLat
            val bx = (line[k + 1].lon - p.lon) * mPerLon
            val by = (line[k + 1].lat - p.lat) * mPerLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            best = minOf(best, kotlin.math.sqrt(x * x + y * y))
        }
        return best
    }
}
