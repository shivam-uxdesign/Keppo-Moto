package com.ridetrack.app.keepmoving

/**
 * Keep moving, step 1: how clear each of Google's routes is, and which to ride.
 *
 * The weights come from the rider's own experience of Google's colours in Delhi (23 Nov):
 * red (jammed) means stopped; yellow (slow) is jammed about 1 time in 5 and otherwise slow-moving.
 * So per metre, red costs [JAM_PER_M], yellow costs a fifth of a jam plus four fifths of a crawl
 * ([SLOW_PER_M], about a third of red), and a road with no traffic data scores as yellow (6 Nov:
 * avoided, used only when nothing with data is clear). Clear road costs almost nothing: time and
 * distance don't matter, it only breaks ties. Routes over [LENGTH_CAP] × the shortest are out.
 * Step 0's rides check these numbers against the rider's real speed.
 */
/**
 * How the rider wants to ride (picked before Start, remembered): Normal is Google's own fastest
 * route (Keppo doesn't score it); Less traffic and No foot down score Google's routes with their
 * own weights and switch rules. No foot down treats yellow as likely feet-down and counts signals.
 */
enum class KeepMode(
    val label: String,
    /** Chance a yellow stretch makes you stop. */
    val yellowJamChance: Double,
    /** Longest detour, × the shortest route. */
    val lengthCap: Double,
    /** A new road must cost at most this share of the current one... */
    val betterShare: Double,
    /** ...and save at least this much. */
    val minGain: Double,
    /** Trouble ahead worth a look (or a warning). */
    val lookCost: Double,
    /** What one traffic signal costs (about this many metres of red). */
    val signalCost: Double,
) {
    NORMAL("Normal", 0.2, 3.0, 0.7, 150.0, 150.0, 0.0),
    LESS_TRAFFIC("Less traffic", 0.2, 2.0, 0.7, 150.0, 150.0, 0.0),
    NO_FOOT_DOWN("No foot down", 0.55, 3.0, 0.8, 75.0, 75.0, 60.0);

    /** Yellow's cost per metre: a share of a jam plus a crawl for the rest. */
    val slowPerM: Double get() = yellowJamChance * RouteScore.JAM_PER_M + (1 - yellowJamChance) * RouteScore.CRAWL_PER_M

    /** Keppo plans this mode's route itself (Normal leaves it to Google). */
    val scored: Boolean get() = this != NORMAL

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: LESS_TRAFFIC
    }
}

object RouteScore {
    const val JAM_PER_M = 1.0
    /** Chance a yellow stretch is really jammed (the rider: "a 20% chance"). */
    const val YELLOW_JAM_CHANCE = 0.2
    /** What a metre of slow-moving traffic costs when it isn't jammed. */
    const val CRAWL_PER_M = 0.15
    const val SLOW_PER_M = YELLOW_JAM_CHANCE * JAM_PER_M + (1 - YELLOW_JAM_CHANCE) * CRAWL_PER_M
    /** No traffic data: scored as slow. */
    const val UNKNOWN_PER_M = SLOW_PER_M
    /** Clear road: only enough that, of two equally clear routes, the shorter wins. */
    const val CLEAR_PER_M = 0.005
    /** The clearest route may be up to this many times the shortest one's length. */
    const val LENGTH_CAP = 3.0

    /** [route]'s metres by colour, measured along its own line. */
    fun colours(route: TrafficRoute): Colours {
        val points = TrafficRoutes.decodePolyline(route.polyline)
        val total = (0 until points.size - 1).sumOf { TrafficRoutes.distanceM(points[it], points[it + 1]) }
        val clear = TrafficRoutes.metres(points, route.intervals, Speed.NORMAL)
        val slow = TrafficRoutes.metres(points, route.intervals, Speed.SLOW)
        val jam = TrafficRoutes.metres(points, route.intervals, Speed.JAM)
        // Stretches Google gave no reading for (or called unknown).
        val unknown = (total.toInt() - clear - slow - jam).coerceAtLeast(0)
        return Colours(clear, slow, jam, unknown)
    }

    fun cost(c: Colours, mode: KeepMode = KeepMode.LESS_TRAFFIC, signals: Int = 0): Double =
        c.jamM * JAM_PER_M + (c.slowM + c.unknownM) * mode.slowPerM + c.clearM * CLEAR_PER_M + signals * mode.signalCost

    /**
     * [routes] ranked clearest first; routes over the length cap come last, marked. The first
     * route that isn't too long is the one to ride. Index is the route's place in Google's answer.
     */
    fun rank(routes: List<TrafficRoute>, mode: KeepMode = KeepMode.LESS_TRAFFIC, signals: (TrafficRoute) -> Int = { 0 }, shortestM: Int? = null): List<Ranked> {
        if (routes.isEmpty()) return emptyList()
        val shortest = (shortestM ?: routes.minOf { it.distanceM }).coerceAtLeast(1)
        val all = routes.mapIndexed { i, r ->
            val c = colours(r)
            val n = signals(r)
            Ranked(i, r, c, cost(c, mode, n), tooLong = r.distanceM > shortest * mode.lengthCap, signals = n)
        }
        return all.sortedWith(compareBy<Ranked> { it.tooLong }.thenBy { it.cost })
    }

    /** [route]'s line cut into stretches of one colour each, for drawing (gaps are [Speed.UNKNOWN]). */
    fun stretches(route: TrafficRoute): List<Stretch> {
        val points = TrafficRoutes.decodePolyline(route.polyline)
        if (points.size < 2) return emptyList()
        val speedAt = Array(points.size - 1) { Speed.UNKNOWN }
        route.intervals.forEach { iv ->
            for (k in iv.start.coerceAtLeast(0) until iv.end.coerceAtMost(points.size - 1)) speedAt[k] = iv.speed
        }
        val out = ArrayList<Stretch>()
        var from = 0
        for (k in 1..speedAt.size) {
            if (k == speedAt.size || speedAt[k] != speedAt[from]) {
                out += Stretch(points.subList(from, k + 1), speedAt[from])
                from = k
            }
        }
        return out
    }
}

/** Metres of a route by Google's colour. */
data class Colours(val clearM: Int, val slowM: Int, val jamM: Int, val unknownM: Int)

/** One route, scored. Lower [cost] is clearer. */
data class Ranked(val index: Int, val route: TrafficRoute, val colours: Colours, val cost: Double, val tooLong: Boolean, val signals: Int = 0) {
    /** Nothing red and under 100 m of yellow / no data: as clear as it gets. */
    val clear: Boolean get() = colours.jamM == 0 && colours.slowM + colours.unknownM < 100
}

/** Consecutive points of a route at one speed. */
data class Stretch(val points: List<LatLon>, val speed: Speed)
