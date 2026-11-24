package com.ridetrack.app.keepmoving

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Keep moving: the route before the ride. Google's routes first; when every one has jams (Less
 * traffic, No foot down), ways round each jam are tried: points on Google's other routes that go
 * round it, then points from the rider's own rides, then a guessed point to either side, each
 * asked as "from here to there through this point". The clearest result wins.
 */
class Planner(
    private val context: Context,
    private val log: TrafficLogger,
    /** Step 4: the rider's own traffic memory. */
    private val memory: () -> Map<Memory.Cell, Memory.Stat> = { emptyMap() },
) {

    /** One plannable route: what Google said, how it scored, and the detour points it passes. */
    data class Option(val ranked: Ranked, val via: List<LatLon>)

    /** What the search did, as the screen shows it. */
    data class Step(val title: String, val detail: String, val ok: Boolean)

    data class Progress(val tried: Int, val budget: Int, val steps: List<Step>)

    data class Plan(
        /** Google's own first answer (its fastest), for the trade-off card. */
        val google: Ranked?,
        /** The routes to choose from, clearest first (the first is picked). */
        val options: List<Option>,
        /** Routes API requests the plan made. */
        val requests: Int,
        /** Signals were counted (No foot down, OpenStreetMap reachable). */
        val signalsCounted: Boolean,
        val carRoutes: Boolean,
        val problem: String?,
    )

    /**
     * Plans [from] → [to] in [mode]. Normal makes no request (Google's navigation plans it).
     * [ownRides]: points of the rider's past rides, for detour candidates.
     */
    suspend fun plan(
        from: LatLon,
        to: LatLon,
        mode: KeepMode,
        ownRides: List<LatLon> = emptyList(),
        onProgress: (Progress) -> Unit = {},
    ): Plan {
        if (!mode.scored) return Plan(null, emptyList(), 0, false, false, null)
        var requests = 1
        // 24 Nov: No foot down took 15 s+, mostly OpenStreetMap's slow free server. The signals now
        // load alongside Google's answer (for the box around the trip), and the plan waits at most
        // SIGNALS_WAIT_MS for them; the fetch carries on and fills the cache for next time.
        val signalsJob = if (mode.signalCost > 0) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { Signals.around(context, box(from, to)) }
        } else null
        val first = log.routes(from, to)
        if (first.status != 200 || first.routes.isEmpty()) {
            return Plan(null, emptyList(), requests, false, false, first.problem ?: "Google has no route there")
        }
        val car = first.mode == TrafficRoutes.DRIVE
        val shortest = first.routes.minOf { it.distanceM }
        val signals = signalsJob?.let { kotlinx.coroutines.withTimeoutOrNull(SIGNALS_WAIT_MS) { it.await() } }.orEmpty()
        fun count(r: TrafficRoute) = Signals.count(TrafficRoutes.decodePolyline(r.polyline), signals)
        // Your own record at this hour moves each route's cost toward how you actually ride there.
        val mem = memory()
        val bucket = Memory.bucket(System.currentTimeMillis())
        fun own(r: TrafficRoute) = Memory.adjust(mem, r, mode, bucket)
        val ranked = RouteScore.rank(first.routes, mode, ::count, adjust = ::own)
        // Detours from roads where you're actually moving at this hour, else any road you've ridden.
        val ownWays = Memory.goodPoints(mem, bucket).ifEmpty { ownRides }
        val google = ranked.firstOrNull { it.index == 0 }
        val options = ranked.map { Option(it, emptyList()) }.toMutableList()
        val best = options.firstOrNull { !it.ranked.tooLong }
        if (best == null || best.ranked.clear) {
            return Plan(google, options, requests, signals.isNotEmpty(), car, null)
        }

        // Every route has trouble: try ways round each jam on the best one.
        val budget = if (mode == KeepMode.NO_FOOT_DOWN) 6 else 3
        val bestLine = TrafficRoutes.decodePolyline(best.ranked.route.polyline)
        val others = ranked.filter { it !== best.ranked }.map { TrafficRoutes.decodePolyline(it.route.polyline) }
        val jams = jams(best.ranked.route, MIN_JAM_M).take(3)
        val steps = ArrayList<Step>()
        var bestSoFar: Option = best
        onProgress(Progress(0, budget, steps.toList()))
        // Every jam's ways round at once, one point each (24 Nov: one round instead of jam by jam).
        val perJam = maxOf(1, budget / maxOf(1, jams.size)).coerceAtMost(2)
        val tries = jams.withIndex().flatMap { (n, jam) -> candidates(jam, bestLine, others, ownWays).take(perJam).map { Triple(n, jam, it) } }.take(budget)
        val results = coroutineScope {
            tries.map { (n, jam, c) -> async { Triple(n, jam, c) to log.routes(from, to, via = listOf(c.point)) } }.awaitAll()
        }
        requests += results.size
        val bestPerJam = HashMap<Int, Pair<Option, LatLon>>()
        fun consider(label: String, source: String, via: List<LatLon>, a: RoutesAnswer): Option? {
            val route = a.routes.firstOrNull()
            if (a.status != 200 || route == null) {
                steps += Step(label, "$source: Google had no route", false)
                return null
            }
            val line = TrafficRoutes.decodePolyline(route.polyline)
            if (hasSpur(line, via)) {
                steps += Step(label, "$source: goes in and comes back out, dropped", false)
                return null
            }
            val r = RouteScore.rank(listOf(route), mode, ::count, shortestM = shortest, adjust = ::own).first()
            val opt = Option(r, via)
            options += opt
            val better = !r.tooLong && r.cost < best.ranked.cost
            steps += Step(label, "$source: " + if (r.tooLong) "too long, dropped" else if (better) describe(r) else "no clearer", better)
            if (!r.tooLong && r.cost < bestSoFar.ranked.cost) bestSoFar = opt
            return opt.takeIf { better }
        }
        for ((t, a) in results) {
            val (n, jam, c) = t
            val opt = consider("Around jam ${n + 1} · ${km(jam.lengthM)}", c.source, listOf(c.point), a) ?: continue
            if (bestPerJam[n]?.first?.ranked?.cost?.let { it > opt.ranked.cost } != false) bestPerJam[n] = opt to c.point
        }
        onProgress(Progress(results.size, budget, steps.toList()))
        // Two or more jams each had a way round: one more try going round all of them.
        if (bestPerJam.size >= 2 && !bestSoFar.ranked.clear) {
            val via = order(bestPerJam.values.map { it.second }, bestLine)
            requests++
            consider("Round ${bestPerJam.size} jams", "the best ways round together", via, log.routes(from, to, via = via))
            onProgress(Progress(results.size + 1, budget + 1, steps.toList()))
        }
        val sorted = options.sortedWith(compareBy<Option> { it.ranked.tooLong }.thenBy { it.ranked.cost })
        return Plan(google, sorted, requests, signals.isNotEmpty(), car, null)
    }

    companion object {
        /** Jams shorter than this aren't worth a detour. */
        const val MIN_JAM_M = 200
        /** The plan waits at most this long for OpenStreetMap's signals. */
        const val SIGNALS_WAIT_MS = 4_000L

        /** The box around a trip from [a] to [b], with 2 km to spare (for the signals). */
        fun box(a: LatLon, b: LatLon): List<LatLon> {
            val pad = 0.018
            return listOf(
                LatLon(minOf(a.lat, b.lat) - pad, minOf(a.lon, b.lon) - pad),
                LatLon(maxOf(a.lat, b.lat) + pad, maxOf(a.lon, b.lon) + pad),
            )
        }
        /** A way round passes at least this far from the jammed road... */
        const val ROUND_MIN_M = 150.0
        /** ...and within this of the jam. */
        const val ROUND_NEAR_M = 1_500.0
        /** A guessed point this far to either side of the jam. */
        const val SIDE_M = 700.0
        /** Spurs are looked for this close to a detour point. */
        const val SPUR_NEAR_M = 400.0

        /** One red stretch on a route: where it is and how long. */
        data class Jam(val start: LatLon, val end: LatLon, val mid: LatLon, val lengthM: Int, val fromIndex: Int)

        /** A detour point and where it came from ("Google's other route", "Your ride", "A side road"). */
        data class Candidate(val point: LatLon, val source: String)

        /** [route]'s red stretches of at least [minM], in order along it. */
        fun jams(route: TrafficRoute, minM: Int): List<Jam> {
            val pts = TrafficRoutes.decodePolyline(route.polyline)
            return route.intervals.filter { it.speed == Speed.JAM }.mapNotNull { iv ->
                val a = iv.start.coerceIn(0, pts.size - 1)
                val b = iv.end.coerceIn(0, pts.size - 1)
                if (b <= a) return@mapNotNull null
                val len = (a until b).sumOf { TrafficRoutes.distanceM(pts[it], pts[it + 1]) }.toInt()
                if (len < minM) return@mapNotNull null
                Jam(pts[a], pts[b], pts[(a + b) / 2], len, a)
            }.sortedBy { it.fromIndex }
        }

        /**
         * Points to try going round [jam]: on Google's [others] routes where they pass it at a
         * distance, then from the rider's [own] rides, then a guess to either side. Real roads first.
         */
        fun candidates(jam: Jam, line: List<LatLon>, others: List<List<LatLon>>, own: List<LatLon>): List<Candidate> {
            fun roundAbout(p: LatLon) = TrafficRoutes.distanceM(p, jam.mid) <= ROUND_NEAR_M &&
                Reroute.distanceToLineM(p, line) >= ROUND_MIN_M
            val fromGoogle = others.mapNotNull { o -> o.filter(::roundAbout).maxByOrNull { Reroute.distanceToLineM(it, line) } }
                .map { Candidate(it, "Google's other route") }
            val fromOwn = own.filter(::roundAbout).sortedBy { TrafficRoutes.distanceM(it, jam.mid) }.take(1)
                .map { Candidate(it, "Your own ride") }
            val sides = side(jam).map { Candidate(it, "A side road") }
            return (fromGoogle + fromOwn + sides).distinctBy { "%.3f,%.3f".format(it.point.lat, it.point.lon) }
        }

        /** Points [SIDE_M] to the left and right of the jam's middle, square to its direction. */
        fun side(jam: Jam): List<LatLon> {
            val mPerLat = 111_320.0
            val mPerLon = 111_320.0 * cos(Math.toRadians(jam.mid.lat))
            val dx = (jam.end.lon - jam.start.lon) * mPerLon
            val dy = (jam.end.lat - jam.start.lat) * mPerLat
            val len = sqrt(dx * dx + dy * dy).takeIf { it > 0 } ?: return emptyList()
            val nx = -dy / len * SIDE_M
            val ny = dx / len * SIDE_M
            return listOf(1, -1).map { s -> LatLon(jam.mid.lat + s * ny / mPerLat, jam.mid.lon + s * nx / mPerLon) }
        }

        /** [points] in the order they come along [line] (nearest point's index). */
        fun order(points: List<LatLon>, line: List<LatLon>): List<LatLon> =
            points.sortedBy { p -> line.indices.minByOrNull { TrafficRoutes.distanceM(line[it], p) } ?: 0 }

        /**
         * The line comes back over itself near a detour point (went into a lane to reach the point
         * and back out): sampled every ~50 m, only within [SPUR_NEAR_M] of [via] (a U-turn under a
         * flyover elsewhere is a real road, not a spur).
         */
        fun hasSpur(line: List<LatLon>, via: List<LatLon>): Boolean {
            val samples = ArrayList<LatLon>()
            var run = 0.0
            line.forEachIndexed { i, p ->
                if (i == 0) { samples += p; return@forEachIndexed }
                run += TrafficRoutes.distanceM(line[i - 1], p)
                if (run >= 50) { samples += p; run = 0.0 }
            }
            fun nearVia(p: LatLon) = via.any { TrafficRoutes.distanceM(it, p) <= SPUR_NEAR_M }
            for (i in samples.indices) {
                if (!nearVia(samples[i])) continue
                for (j in 0 until i - 6) {
                    if (nearVia(samples[j]) && TrafficRoutes.distanceM(samples[i], samples[j]) < 25) return true
                }
            }
            return false
        }

        fun km(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 10) * 10} m"

        fun describe(r: Ranked): String = listOfNotNull(
            if (r.colours.jamM == 0) "no jams" else "${km(r.colours.jamM)} jammed",
            km(r.colours.slowM).takeIf { r.colours.slowM > 0 }?.let { "$it slow" },
        ).joinToString(" · ")
    }
}
