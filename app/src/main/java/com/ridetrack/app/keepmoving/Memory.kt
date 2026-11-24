package com.ridetrack.app.keepmoving

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor

/**
 * Keep moving, step 4: how the rider actually moves on each stretch of road, at each hour,
 * learned from their own rides (on the phone only). Roads are cut into [CELL_DEG] cells (about
 * 100 m in Delhi), kept apart by direction ([DIRS]) and by weekday / weekend and hour.
 *
 * This file is the pure part (cells, learning from a ride, reading it back), so it's unit-tested;
 * [TrafficMemory] keeps it on the phone and feeds it new rides.
 */
object Memory {
    const val CELL_DEG = 0.001
    const val DIRS = 8
    /** Stopped: under 5 km/h. Crawling: 5–15 km/h. */
    const val STOP_MPS = 5 / 3.6
    const val CRAWL_MPS = 15 / 3.6
    /**
     * Standing still (under [REST_MPS], within [REST_M] of where it began) for this long is a rest
     * stop, not traffic: left out. Creeping along in a jam, however slowly, is kept.
     */
    const val REST_MS = 5 * 60_000L
    const val REST_MPS = 0.5
    const val REST_M = 20.0
    /** The first and last minutes of a ride are parking: left out. */
    const val EDGE_MS = 2 * 60_000L
    /** One fix counts for at most this long (gaps don't count as time on the road). */
    const val MAX_DT_S = 5.0
    /** Rides through a cell (at that hour) for the full weight. */
    const val FULL_RIDES = 5
    /** Your record counts for at most this share against Google's. */
    const val MAX_WEIGHT = 0.6

    data class Cell(val row: Int, val col: Int, val dir: Int, val bucket: Int)

    /** Time on a cell at one hour: seconds there, stopped, crawling; speed × seconds; rides. */
    data class Stat(val secs: Double, val stopped: Double, val crawl: Double, val speedSecs: Double, val rides: Int) {
        val stopShare: Double get() = if (secs > 0) stopped / secs else 0.0
        val crawlShare: Double get() = if (secs > 0) crawl / secs else 0.0
        val speedMps: Double get() = if (secs > 0) speedSecs / secs else 0.0
        operator fun plus(o: Stat) = Stat(secs + o.secs, stopped + o.stopped, crawl + o.crawl, speedSecs + o.speedSecs, rides + o.rides)

        /** What a metre here costs by your own record ([RouteScore]'s units). */
        fun costPerM(): Double = stopShare * RouteScore.JAM_PER_M + crawlShare * RouteScore.CRAWL_PER_M +
            (1 - stopShare - crawlShare).coerceAtLeast(0.0) * RouteScore.CLEAR_PER_M

        /** How much your record counts against Google's here (0 to [MAX_WEIGHT]). */
        fun weight(): Double = MAX_WEIGHT * minOf(rides, FULL_RIDES) / FULL_RIDES.toDouble()

        companion object { val EMPTY = Stat(0.0, 0.0, 0.0, 0.0, 0) }
    }

    /** One fix of a ride. */
    data class Fix(val t: Long, val at: LatLon, val speedMps: Double?, val headingDeg: Double?)

    fun row(lat: Double) = floor(lat / CELL_DEG).toInt()
    fun col(lon: Double) = floor(lon / CELL_DEG).toInt()
    fun centre(row: Int, col: Int) = LatLon((row + 0.5) * CELL_DEG, (col + 0.5) * CELL_DEG)
    fun dir(headingDeg: Double): Int = (((headingDeg % 360 + 360) % 360 + 360.0 / DIRS / 2) / (360.0 / DIRS)).toInt() % DIRS

    /** Weekday or weekend, and the hour (local time): 0–23 weekdays, 24–47 weekends. */
    fun bucket(t: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zone).apply { timeInMillis = t }
        val weekend = c.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        return (if (weekend) 24 else 0) + c.get(Calendar.HOUR_OF_DAY)
    }

    /** The hour [b] and the one before and after it (same weekday / weekend). */
    fun near(b: Int): List<Int> {
        val base = if (b >= 24) 24 else 0
        val h = b - base
        return listOf((h + 23) % 24, h, (h + 1) % 24).map { base + it }
    }

    /** Compass bearing from [a] to [b]. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    /**
     * What one ride adds: time per cell, with rest stops and the parking minutes at either end
     * left out. Each cell the ride touched counts as one ride there.
     */
    fun learn(fixes: List<Fix>, zone: TimeZone = TimeZone.getDefault()): Map<Cell, Stat> {
        val f = fixes.sortedBy { it.t }
        if (f.size < 3) return emptyMap()
        val start = f.first().t + EDGE_MS
        val end = f.last().t - EDGE_MS
        // Rest stops: standing still (not creeping) in one spot for longer than REST_MS.
        val rest = BooleanArray(f.size)
        var i = 0
        while (i < f.size) {
            if (speed(f, i) < REST_MPS) {
                var j = i
                while (j + 1 < f.size && speed(f, j + 1) < REST_MPS && TrafficRoutes.distanceM(f[i].at, f[j + 1].at) <= REST_M) j++
                if (f[j].t - f[i].t > REST_MS) for (k in i..j) rest[k] = true
                i = j + 1
            } else {
                i++
            }
        }
        val out = HashMap<Cell, Stat>()
        var heading: Double? = null
        for (k in 0 until f.size - 1) {
            val a = f[k]
            val dt = ((f[k + 1].t - a.t) / 1000.0).coerceIn(0.0, MAX_DT_S)
            val moved = TrafficRoutes.distanceM(a.at, f[k + 1].at)
            heading = a.headingDeg ?: if (moved > 3) bearing(a.at, f[k + 1].at) else heading
            if (rest[k] || a.t < start || a.t > end || dt <= 0) continue
            val h = heading ?: continue
            val v = speed(f, k)
            val cell = Cell(row(a.at.lat), col(a.at.lon), dir(h), bucket(a.t, zone))
            val add = Stat(dt, if (v < STOP_MPS) dt else 0.0, if (v >= STOP_MPS && v < CRAWL_MPS) dt else 0.0, v * dt, 0)
            out[cell] = (out[cell] ?: Stat.EMPTY) + add
        }
        return out.mapValues { it.value.copy(rides = 1) }
    }

    private fun speed(f: List<Fix>, k: Int): Double = f[k].speedMps ?: if (k + 1 < f.size) {
        val dt = (f[k + 1].t - f[k].t) / 1000.0
        if (dt > 0) TrafficRoutes.distanceM(f[k].at, f[k + 1].at) / dt else 0.0
    } else 0.0

    /** Your record at [at] heading [headingDeg], at hour [bucket] ± 1 (this direction and the two beside it). */
    fun at(stats: Map<Cell, Stat>, at: LatLon, headingDeg: Double, bucket: Int): Stat {
        val r = row(at.lat)
        val c = col(at.lon)
        val d = dir(headingDeg)
        var sum = Stat.EMPTY
        for (b in near(bucket)) for (dd in listOf(d, (d + 1) % DIRS, (d + DIRS - 1) % DIRS)) {
            stats[Cell(r, c, dd, b)]?.let { sum += it }
        }
        return sum
    }

    /** Points every ~[stepM] along [line], with the direction of travel there. */
    fun walk(line: List<LatLon>, stepM: Double = 50.0): List<Pair<LatLon, Double>> {
        val out = ArrayList<Pair<LatLon, Double>>()
        var run = stepM
        for (k in 0 until line.size - 1) {
            val d = TrafficRoutes.distanceM(line[k], line[k + 1])
            run += d
            if (run >= stepM) {
                out += line[k] to bearing(line[k], line[k + 1])
                run = 0.0
            }
        }
        return out
    }

    /**
     * How much [route]'s cost changes once your own record is counted: along it, every 50 m where
     * you have a record at this hour, the cost moves from Google's toward yours by the record's weight.
     */
    fun adjust(stats: Map<Cell, Stat>, route: TrafficRoute, mode: KeepMode, bucket: Int): Double {
        if (stats.isEmpty()) return 0.0
        val line = TrafficRoutes.decodePolyline(route.polyline)
        val speedAt = Array(maxOf(line.size - 1, 0)) { Speed.UNKNOWN }
        route.intervals.forEach { iv -> for (k in iv.start.coerceAtLeast(0) until iv.end.coerceAtMost(speedAt.size)) speedAt[k] = iv.speed }
        var delta = 0.0
        var run = 50.0
        for (k in 0 until line.size - 1) {
            run += TrafficRoutes.distanceM(line[k], line[k + 1])
            if (run < 50.0) continue
            run = 0.0
            val s = at(stats, line[k], bearing(line[k], line[k + 1]), bucket)
            if (s.rides == 0 || s.secs < 5) continue
            val google = when (speedAt[k]) {
                Speed.NORMAL -> RouteScore.CLEAR_PER_M
                Speed.JAM -> RouteScore.JAM_PER_M
                else -> mode.slowPerM
            }
            delta += s.weight() * (s.costPerM() - google) * 50.0
        }
        return delta
    }

    /** Trouble by your own record on the road ahead (within [withinM]): where it starts, how long. */
    fun troubleAhead(stats: Map<Cell, Stat>, line: List<LatLon>, bucket: Int, withinM: Int = RideWatch.FAR_M): RideWatch.Trouble? {
        var run = 0.0
        var startAt: Double? = null
        var startPoint: LatLon? = null
        for ((p, h) in walk(line)) {
            if (run > withinM && startAt == null) return null
            val s = at(stats, p, h, bucket)
            val slow = s.rides >= 3 && s.stopShare >= 0.4
            if (slow && startAt == null) { startAt = run; startPoint = p }
            if (!slow && startAt != null) {
                val len = (run - startAt).toInt()
                if (len >= RideWatch.MIN_JAM_M) return RideWatch.Trouble(startAt.toInt(), len, jammed = true, at = startPoint)
                startAt = null
            }
            run += 50.0
        }
        return startAt?.let { RideWatch.Trouble(it.toInt(), (run - it).toInt(), true, startPoint) }
    }

    /** Cells where you're moving well at this hour (detour candidates, ways round): their centres. */
    fun goodPoints(stats: Map<Cell, Stat>, bucket: Int): List<LatLon> {
        val hours = near(bucket).toSet()
        return stats.entries.filter { it.key.bucket in hours }
            .groupBy { it.key.row to it.key.col }
            .mapNotNull { (rc, list) ->
                val s = list.fold(Stat.EMPTY) { a, e -> a + e.value }
                if (s.rides >= 2 && s.stopShare < 0.15 && s.speedMps > 15 / 3.6) centre(rc.first, rc.second) else null
            }
    }

    /**
     * Your own yellow: of the yellow stretches Google showed ahead of you (in a ride's traffic log)
     * that you then rode through, how many you stopped in. [prior] trips at 1 in 5 start it off.
     */
    fun yellowChance(trips: Int, stops: Int, prior: Int = 10): Double = (stops + prior * 0.2) / (trips + prior)

    /**
     * Yellow stretches from one ride's traffic log ([lines], `traffic-log.jsonl`) checked against
     * what was ridden ([fixes]): (trips through yellow, trips that stopped in it). A stretch counts
     * once per ride, when it started within 3 km ahead and the ride then went through most of it.
     */
    fun yellowFromLog(lines: List<String>, fixes: List<Fix>): Pair<Int, Int> {
        val f = fixes.sortedBy { it.t }
        val seen = HashSet<String>()
        var trips = 0
        var stops = 0
        for (raw in lines) {
            val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: continue
            if (o.optString("type") != "check") continue
            val t = o.optLong("t")
            val r = o.optJSONArray("routes")?.optJSONObject(0) ?: continue
            val line = TrafficRoutes.decodePolyline(r.optString("polyline"))
            val iv = r.optJSONArray("intervals") ?: continue
            for (i in 0 until iv.length()) {
                val a = iv.optJSONArray(i) ?: continue
                if (a.optString(2) != Speed.SLOW.code) continue
                val s0 = a.optInt(0).coerceIn(0, maxOf(line.size - 1, 0))
                val s1 = a.optInt(1).coerceIn(0, maxOf(line.size - 1, 0))
                if (s1 <= s0) continue
                val stretch = line.subList(s0, s1 + 1)
                val len = (0 until stretch.size - 1).sumOf { TrafficRoutes.distanceM(stretch[it], stretch[it + 1]) }
                val ahead = (0 until s0).sumOf { TrafficRoutes.distanceM(line[it], line[it + 1]) }
                if (len < 100 || ahead > 3_000) continue
                val key = "%.3f,%.3f".format(stretch.first().lat, stretch.first().lon)
                if (!seen.add(key)) continue
                val inside = f.filter { it.t in t..(t + 20 * 60_000L) && Reroute.distanceToLineM(it.at, stretch) <= 30.0 }
                if (inside.size < 3) continue
                val covered = TrafficRoutes.distanceM(inside.first().at, inside.last().at)
                if (covered < 0.6 * len) continue
                trips++
                // A stop: under 5 km/h for 10 s or more inside the stretch.
                var stoppedFrom: Long? = null
                var stopped = false
                for (x in inside) {
                    if ((x.speedMps ?: 99.0) < STOP_MPS) {
                        if (stoppedFrom == null) stoppedFrom = x.t
                        if (x.t - stoppedFrom >= 10_000) stopped = true
                    } else {
                        stoppedFrom = null
                    }
                }
                if (stopped) stops++
            }
        }
        return trips to stops
    }
}
