package com.ridetrack.app.keepmoving

/**
 * Keep moving, step 2: when to look for a clearer road during a ride, and when to switch to it.
 *
 * The road being ridden is scored from the Navigation SDK's own traffic on it (free, updated by
 * Google as you go), with [RouteScore]'s weights. Google's Routes API (a paid request) is asked
 * only when that road has trouble ahead, and at most every [MIN_ASK_GAP_MS]. A new road wins only
 * when it's clearly better by the mode's rule ([KeepMode.betterShare], [KeepMode.minGain]), so the
 * route doesn't flip back and forth; there's no cooldown after a switch or a "Keep this road".
 */
object Reroute {
    /** Look this far ahead on the current road for trouble. */
    const val AHEAD_M = 5_000
    /** At most one paid look this often (each costs). */
    const val MIN_ASK_GAP_MS = 90_000L
    /** "Keep this road" can be tapped for this long before the switch happens (24 Nov: 5 s, no cooldown after). */
    const val COUNTDOWN_MS = 5_000L
    /** A road the rider declined comes back only when it's this much clearer than when declined. */
    const val DECLINED_BETTER_SHARE = 0.8
    /** Two lines are the same road when this share of the new one is this close to the old. */
    const val SAME_SHARE = 0.8
    const val SAME_WITHIN_M = 60.0

    /** A road the rider said no to ("Keep this road"), and how it scored then. */
    data class Declined(val line: List<LatLon>, val cost: Double)

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

    /** The trouble in the first [AHEAD_M] of the current road (its clear part left out). */
    fun troubleAhead(stretches: List<Stretch>, totalM: Int, mode: KeepMode): Double {
        val c = colours(stretches, totalM, AHEAD_M)
        return c.jamM * RouteScore.JAM_PER_M + (c.slowM + c.unknownM) * mode.slowPerM
    }

    /** Trouble ahead on the current road, and the last paid look long enough ago: ask Google. */
    fun shouldLook(stretches: List<Stretch>, totalM: Int, nowMs: Long, lastAskMs: Long, mode: KeepMode): Boolean =
        mode.scored && nowMs - lastAskMs >= MIN_ASK_GAP_MS && troubleAhead(stretches, totalM, mode) >= mode.lookCost

    /**
     * The road to switch to, or null to stay: the clearest of Google's [ranked] routes, when it
     * isn't the road already ridden ([current] line), is clearly better than its [currentCost]
     * by [mode]'s rule, and isn't a road the rider declined (unless it's become clearly clearer).
     */
    fun pick(ranked: List<Ranked>, current: List<LatLon>, currentCost: Double, mode: KeepMode = KeepMode.LESS_TRAFFIC, declined: List<Declined> = emptyList()): Ranked? {
        val best = ranked.firstOrNull { !it.tooLong } ?: return null
        if (best.route.routeToken == null) return null
        val line = TrafficRoutes.decodePolyline(best.route.polyline)
        if (sameRoad(current, line)) return null
        if (declined.any { d -> sameRoad(d.line, line) && best.cost > d.cost * DECLINED_BETTER_SHARE }) return null
        val better = best.cost <= currentCost * mode.betterShare && currentCost - best.cost >= mode.minGain
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
