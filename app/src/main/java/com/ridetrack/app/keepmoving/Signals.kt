package com.ridetrack.app.keepmoving

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor

/**
 * Traffic signals for No foot down: OpenStreetMap's mapped traffic lights (© OpenStreetMap
 * contributors, ODbL), fetched by the phone from the Overpass API for the area a ride is planned
 * in and kept for a week, in tiles of [TILE_DEG]. India's mapping is incomplete: a nudge, not a
 * promise. Google's routes don't list signals, so they're counted along each route here.
 */
object Signals {
    const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    const val TILE_DEG = 0.05
    /** At most this many tiles for one plan (about 40 × 30 km in Delhi). */
    const val MAX_TILES = 48
    private const val KEEP_MS = 7 * 24 * 3_600_000L
    /** A signal this close to a route's line is on it. */
    const val ON_ROUTE_M = 25.0
    /** Signal nodes this close together are one junction (a junction has a light per arm). */
    const val SAME_JUNCTION_M = 40.0

    /** The tiles that cover [points] (row, col), or empty when that's more than [MAX_TILES]. */
    fun tiles(points: List<LatLon>): List<Pair<Int, Int>> {
        if (points.isEmpty()) return emptyList()
        val r0 = floor(points.minOf { it.lat } / TILE_DEG).toInt()
        val r1 = floor(points.maxOf { it.lat } / TILE_DEG).toInt()
        val c0 = floor(points.minOf { it.lon } / TILE_DEG).toInt()
        val c1 = floor(points.maxOf { it.lon } / TILE_DEG).toInt()
        if ((r1 - r0 + 1) * (c1 - c0 + 1) > MAX_TILES) return emptyList()
        return (r0..r1).flatMap { r -> (c0..c1).map { c -> r to c } }
    }

    /** The Overpass query for the box of tile rows r0 to r1 and columns c0 to c1. */
    fun query(r0: Int, r1: Int, c0: Int, c1: Int): String =
        "[out:json][timeout:20];node[highway=traffic_signals](${r0 * TILE_DEG},${c0 * TILE_DEG},${(r1 + 1) * TILE_DEG},${(c1 + 1) * TILE_DEG});out skel;"

    /** Overpass's answer → signal points. */
    fun parse(body: String): List<LatLon> {
        val a = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("lat") || !o.has("lon")) return@mapNotNull null
            LatLon(o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    /** How many junctions with signals [line] passes (each junction counted once). */
    fun count(line: List<LatLon>, signals: List<LatLon>): Int {
        if (line.size < 2 || signals.isEmpty()) return 0
        val minLat = line.minOf { it.lat } - 0.001
        val maxLat = line.maxOf { it.lat } + 0.001
        val minLon = line.minOf { it.lon } - 0.001
        val maxLon = line.maxOf { it.lon } + 0.001
        val on = signals.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .filter { Reroute.distanceToLineM(it, line) <= ON_ROUTE_M }
        val junctions = ArrayList<LatLon>()
        on.forEach { p -> if (junctions.none { TrafficRoutes.distanceM(it, p) < SAME_JUNCTION_M }) junctions += p }
        return junctions.size
    }

    /** The signals around [points] (cached a week); empty when OpenStreetMap can't be reached. */
    suspend fun around(context: Context, points: List<LatLon>): List<LatLon> = withContext(Dispatchers.IO) {
        val t = tiles(points)
        if (t.isEmpty()) return@withContext emptyList()
        val dir = File(context.cacheDir, "osm-signals").apply { mkdirs() }
        fun file(r: Int, c: Int) = File(dir, "$r-$c.json")
        val now = System.currentTimeMillis()
        val missing = t.filter { (r, c) -> file(r, c).let { !it.exists() || now - it.lastModified() > KEEP_MS } }
        if (missing.isNotEmpty()) {
            runCatching {
                val r0 = missing.minOf { it.first }
                val r1 = missing.maxOf { it.first }
                val c0 = missing.minOf { it.second }
                val c1 = missing.maxOf { it.second }
                val found = parse(post(query(r0, r1, c0, c1)))
                // One file per tile, so a later plan nearby reuses them.
                missing.forEach { (r, c) ->
                    val inTile = found.filter { floor(it.lat / TILE_DEG).toInt() == r && floor(it.lon / TILE_DEG).toInt() == c }
                    file(r, c).writeText(JSONArray().apply { inTile.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }.toString())
                }
            }
        }
        t.flatMap { (r, c) ->
            runCatching {
                val a = JSONArray(file(r, c).readText())
                (0 until a.length()).map { i -> a.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } }
            }.getOrDefault(emptyList())
        }
    }

    private fun post(q: String): String {
        val c = URL(OVERPASS_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.doOutput = true
            // A plain app name: Overpass asks clients to say who they are, nothing personal.
            c.setRequestProperty("User-Agent", "KeppoMoto/0.1 (Android)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) error("Overpass ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
