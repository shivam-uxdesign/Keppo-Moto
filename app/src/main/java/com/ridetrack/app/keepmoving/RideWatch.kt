package com.ridetrack.app.keepmoving

import kotlin.math.cos

/**
 * Keep moving, during the ride (24 Nov plan): free warnings first, the rider's own turn second,
 * the paid switch last. Warnings come at about 1.5 km and 500 m before trouble on the road being
 * ridden (from the navigation's own traffic, free), with a way round when one is known.
 */
object RideWatch {
    /** First warning this far before the trouble... */
    const val FAR_M = 1_600
    /** ...the second (and the paid look, when the rider hasn't turned) this far. */
    const val NEAR_M = 600
    /** Red shorter than this isn't worth a warning. */
    const val MIN_JAM_M = 100
    /** In No foot down, yellow this long is warned about too. */
    const val MIN_SLOW_M = 300

    /** Trouble on the road ahead: where it starts (from here) and how long it is. */
    data class Trouble(val inM: Int, val lengthM: Int, val jammed: Boolean, val at: LatLon?)

    /** The first trouble worth a warning within [FAR_M] (red; in No foot down long yellow too). */
    fun firstTrouble(stretches: List<Reroute.Stretch>, line: List<LatLon>, mode: KeepMode): Trouble? =
        stretches.sortedBy { it.offsetM }.firstOrNull { s ->
            s.offsetM <= FAR_M && when (s.speed) {
                Speed.JAM -> s.lengthM >= MIN_JAM_M
                Speed.SLOW -> mode == KeepMode.NO_FOOT_DOWN && s.lengthM >= MIN_SLOW_M
                else -> false
            }
        }?.let { Trouble(it.offsetM, it.lengthM, it.speed == Speed.JAM, pointAt(line, it.offsetM)) }

    /** 0: nothing to say; 1: the far warning; 2: the near one. */
    fun stage(inM: Int): Int = when {
        inM <= NEAR_M -> 2
        inM <= FAR_M -> 1
        else -> 0
    }

    /** The point [offsetM] along [line] (null when the line is shorter). */
    fun pointAt(line: List<LatLon>, offsetM: Int): LatLon? {
        var run = 0.0
        for (k in 0 until line.size - 1) {
            val d = TrafficRoutes.distanceM(line[k], line[k + 1])
            if (run + d >= offsetM) {
                val t = if (d == 0.0) 0.0 else (offsetM - run) / d
                return LatLon(line[k].lat + t * (line[k + 1].lat - line[k].lat), line[k].lon + t * (line[k + 1].lon - line[k].lon))
            }
            run += d
        }
        return null
    }

    /** Which side of the road ahead (going from [from] to [to]) [p] is on: "left" or "right". */
    fun side(from: LatLon, to: LatLon, p: LatLon): String {
        val k = cos(Math.toRadians(from.lat))
        val ax = (to.lon - from.lon) * k
        val ay = to.lat - from.lat
        val bx = (p.lon - from.lon) * k
        val by = p.lat - from.lat
        return if (ax * by - ay * bx > 0) "left" else "right"
    }

    /**
     * A known way round [trouble]: the nearest of [ways] (detour points from the plan, the rider's
     * own rides) within 1.5 km of it and off the road; with the side it's on.
     */
    fun wayRound(trouble: Trouble, line: List<LatLon>, here: LatLon, ways: List<LatLon>): String? {
        val at = trouble.at ?: return null
        val p = ways.filter { TrafficRoutes.distanceM(it, at) <= Planner.ROUND_NEAR_M && Reroute.distanceToLineM(it, line) >= Planner.ROUND_MIN_M }
            .minByOrNull { TrafficRoutes.distanceM(it, at) } ?: return null
        return side(here, at, p)
    }

    /** A rough key for one jam, so its warnings aren't repeated. */
    fun key(t: Trouble): String = t.at?.let { "%.3f,%.3f".format(it.lat, it.lon) } ?: "${t.inM / 500}"

    /** On screen. */
    fun title(t: Trouble): String = (if (t.jammed) "Jam" else "Slow traffic") + " in " + Planner.km(t.inM)

    fun detail(t: Trouble, way: String?): String =
        "${Planner.km(t.lengthM)} " + (if (t.jammed) "of red" else "of slow traffic") +
            (way?.let { " · a way round on your $it" } ?: "")

    /** Our own words, read aloud (never Google's text: its terms rule that out). */
    fun voice(t: Trouble, stage: Int, way: String?): String {
        val what = if (t.jammed) "Jam" else "Slow traffic"
        val distance = if (stage == 1) "in one and a half kilometres" else "in five hundred metres"
        return "$what $distance." + (way?.let { " A way round on your $it." } ?: "")
    }

    const val SWITCH_VOICE = "Traffic ahead. Taking a clearer road."
}
