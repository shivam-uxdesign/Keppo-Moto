package com.ridetrack.app.fuel

import android.content.Context
import com.ridetrack.app.studio.writeAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sqrt

/** A fuel station: its name and where it is; [mine]: the rider has filled up here before. */
data class Station(val name: String, val latitude: Double, val longitude: Double, val mine: Boolean = false)

/**
 * Finds the fuel station at a stop, without needing the internet at that moment:
 *  1. stations the rider has filled up at (remembered where the bike actually stood, so they
 *     work even where the map has the pump in the wrong place, or not at all);
 *  2. stations from OpenStreetMap kept on the phone for the areas the rider rides in (fetched
 *     about 15 km around, refreshed monthly);
 *  3. only then OpenStreetMap online, for a place not kept yet.
 */
class StationFinder(private val context: Context) {
    private val file = File(context.filesDir, "fuel-stations.json")

    private data class Area(val latitude: Double, val longitude: Double, val radiusM: Double, val fetchedAt: Long)
    private data class Store(val mine: List<Station>, val mapped: List<Station>, val areas: List<Area>)

    @Volatile private var store: Store? = null

    /** The station within [radiusM] of a stop (yours first), from what's on the phone; null if none. */
    fun nearKept(lat: Double, lon: Double, radiusM: Double = RADIUS_M): Station? {
        val s = load()
        return Stations.nearest(s.mine, lat, lon, MINE_RADIUS_M) ?: Stations.nearest(s.mapped, lat, lon, radiusM)
    }

    /** Whether the stations around [lat], [lon] are kept on the phone (fresh enough). */
    fun covers(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean =
        load().areas.any { a -> now - a.fetchedAt < REFRESH_MS && Stations.metres(a.latitude, a.longitude, lat, lon) < a.radiusM - 2_000 }

    /**
     * The station at a stop: on the phone first; then, if this place isn't kept yet, the stations
     * around it are fetched (and kept). Throws when that fetch fails (no internet): check again later.
     */
    fun near(lat: Double, lon: Double): Station? {
        nearKept(lat, lon)?.let { return it }
        if (covers(lat, lon)) return null
        fetchArea(lat, lon)
        return nearKept(lat, lon)
    }

    /** Keeps the stations around [lat], [lon] on the phone, if they aren't yet (for the ride-away question, offline). */
    fun keepArea(lat: Double, lon: Double) {
        if (!covers(lat, lon)) runCatching { fetchArea(lat, lon) }
    }

    /** A confirmed fill-up at [name], where the bike stood: remembered as one of your stations. */
    @Synchronized
    fun remember(name: String, lat: Double, lon: Double) {
        val s = load()
        val others = s.mine.filter { Stations.metres(it.latitude, it.longitude, lat, lon) > MINE_RADIUS_M }
        save(s.copy(mine = others + Station(name, lat, lon, mine = true)))
    }

    /** The pumps you filled up at or marked on a ride's map. */
    fun mine(): List<Station> = load().mine

    /** Forgets your pump at [lat], [lon] (the map's stations stay). */
    @Synchronized
    fun forget(lat: Double, lon: Double) {
        val s = load()
        save(s.copy(mine = s.mine.filterNot { Stations.metres(it.latitude, it.longitude, lat, lon) < 1.0 }))
    }

    /** The name the map gives a pump near [lat], [lon], from what's on the phone (for naming one you mark). */
    fun mappedNear(lat: Double, lon: Double, radiusM: Double = RADIUS_M): Station? = Stations.nearest(load().mapped, lat, lon, radiusM)

    @Synchronized
    private fun fetchArea(lat: Double, lon: Double) {
        val q = "[out:json][timeout:25];nwr[\"amenity\"=\"fuel\"](around:${AREA_M.toInt()},$lat,$lon);out center;"
        val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        val found = try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "KeppoMoto/1 (Android)")
            c.outputStream.use { it.write("data=${URLEncoder.encode(q, "UTF-8")}".toByteArray()) }
            if (c.responseCode !in 200..299) error("OpenStreetMap answered ${c.responseCode}")
            Stations.parseOverpass(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
        val s = load()
        val kept = s.mapped.filter { m -> Stations.metres(m.latitude, m.longitude, lat, lon) > AREA_M }
        val areas = s.areas.filter { Stations.metres(it.latitude, it.longitude, lat, lon) > AREA_M / 2 } + Area(lat, lon, AREA_M, System.currentTimeMillis())
        // Bounded: the most recent areas only.
        save(Store(s.mine, (kept + found).takeLast(MAX_MAPPED), areas.takeLast(MAX_AREAS)))
    }

    @Synchronized
    private fun load(): Store {
        store?.let { return it }
        val s = runCatching {
            val o = JSONObject(file.readText())
            fun stations(a: JSONArray?, mine: Boolean) = (0 until (a?.length() ?: 0)).map { i ->
                a!!.getJSONObject(i).let { Station(it.optString("n"), it.getDouble("lat"), it.getDouble("lon"), mine) }
            }
            Store(
                stations(o.optJSONArray("mine"), true), stations(o.optJSONArray("mapped"), false),
                o.optJSONArray("areas")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Area(it.getDouble("lat"), it.getDouble("lon"), it.getDouble("r"), it.getLong("at")) } } }.orEmpty(),
            )
        }.getOrDefault(Store(emptyList(), emptyList(), emptyList()))
        store = s
        return s
    }

    private fun save(s: Store) {
        store = s
        fun stations(list: List<Station>) = JSONArray().apply { list.forEach { put(JSONObject().put("n", it.name).put("lat", it.latitude).put("lon", it.longitude)) } }
        runCatching {
            file.writeAtomically(
                JSONObject().put("mine", stations(s.mine)).put("mapped", stations(s.mapped))
                    .put("areas", JSONArray().apply { s.areas.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("r", it.radiusM).put("at", it.fetchedAt)) } })
                    .toString(),
            )
        }
    }

    companion object {
        /** A mapped pump this far from where the bike stood still counts (big stations). */
        const val RADIUS_M = 150.0
        /** Your own stations are where you stood, so they're matched more tightly. */
        const val MINE_RADIUS_M = 100.0
        private const val AREA_M = 15_000.0
        private const val REFRESH_MS = 30L * 24 * 3600 * 1000
        private const val MAX_AREAS = 40
        private const val MAX_MAPPED = 5_000
    }
}

/** Distances and reading OpenStreetMap's answer. Pure, unit-tested. */
object Stations {
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    fun nearest(list: List<Station>, lat: Double, lon: Double, radiusM: Double): Station? =
        list.map { it to metres(it.latitude, it.longitude, lat, lon) }.filter { it.second <= radiusM }.minByOrNull { it.second }?.first

    /** Fuel stations in an Overpass JSON answer: name (else brand, operator, "Fuel station") and position. */
    fun parseOverpass(json: String): List<Station> = runCatching {
        val a = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONObject(i)
            val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
            val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
            val tags = e.optJSONObject("tags")
            val name = listOf("name", "brand", "operator").firstNotNullOfOrNull { k -> tags?.optString(k)?.takeIf { it.isNotBlank() } } ?: "Fuel station"
            Station(name, lat, lon)
        }
    }.getOrDefault(emptyList())
}
