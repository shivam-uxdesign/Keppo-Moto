package com.ridetrack.app.keepmoving

import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.ride.RidePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Where the rider might be riding to: where recent rides ended, or a place searched for. */
object Destinations {
    /** Ride ends this close together are one place. */
    private const val SAME_PLACE_M = 400.0

    /**
     * The places recent rides ended at, most often first (up to [max]), named by the area
     * (Android's geocoder). Usual routes end at the same few places, so they're here to tap.
     */
    suspend fun recentEnds(rides: RideRepository, places: RidePlaces, max: Int = 6): List<Destination> = withContext(Dispatchers.IO) {
        val ends = rides.observeCompleted().first().sortedByDescending { it.startTimeMillis }.take(15).mapNotNull { r ->
            rides.track(r.id).samples.lastOrNull { it.latitude != null && it.longitude != null }?.let { LatLon(it.latitude!!, it.longitude!!) }
        }
        group(ends).take(max).mapIndexed { i, p ->
            Destination(places.placeAt(p.lat, p.lon) ?: "Ride end ${i + 1}", p)
        }
    }

    /**
     * [d] as it may be saved: a place found with Google's search is saved under the phone's own
     * name for the area (Google's terms don't allow keeping its place names).
     */
    suspend fun forSaving(d: Destination, places: RidePlaces): Destination =
        if (!d.fromGoogle) d else Destination(runCatching { places.placeAt(d.at.lat, d.at.lon) }.getOrNull() ?: "Searched place", d.at, placeId = d.placeId)

    /**
     * Points along the rider's recent rides (every ~[everyN]th fix of the last [maxRides]): roads
     * they've ridden, used as Keep moving's detour candidates and known ways round.
     */
    suspend fun ridePoints(rides: RideRepository, maxRides: Int = 20, everyN: Int = 20): List<LatLon> = withContext(Dispatchers.IO) {
        runCatching {
            rides.observeCompleted().first().sortedByDescending { it.startTimeMillis }.take(maxRides).flatMap { r ->
                rides.track(r.id).samples.filterIndexed { i, _ -> i % everyN == 0 }
                    .mapNotNull { f -> f.latitude?.let { lat -> f.longitude?.let { LatLon(lat, it) } } }
            }
        }.getOrDefault(emptyList())
    }

    /** [points] grouped into places [SAME_PLACE_M] apart, the most visited first (each place where it was first seen). */
    fun group(points: List<LatLon>): List<LatLon> {
        val groups = ArrayList<Pair<LatLon, Int>>()
        points.forEach { p ->
            val i = groups.indexOfFirst { TrafficRoutes.distanceM(it.first, p) < SAME_PLACE_M }
            if (i >= 0) groups[i] = groups[i].first to groups[i].second + 1 else groups += p to 1
        }
        return groups.sortedByDescending { it.second }.map { it.first }
    }

    /** Places matching [query] (MapTiler's search, India first, nearest [near] first); empty when it can't be reached. */
    suspend fun search(query: String, near: LatLon?): List<Destination> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2 || BuildConfig.MAPTILER_KEY.isBlank()) return@withContext emptyList()
        runCatching {
            val url = "https://api.maptiler.com/geocoding/${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}.json" +
                "?key=${BuildConfig.MAPTILER_KEY}&country=in&limit=6&language=en" +
                (near?.let { "&proximity=${it.lon},${it.lat}" } ?: "")
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                parseSearch(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    /** MapTiler's answer → places (its centre is longitude first). */
    fun parseSearch(body: String): List<Destination> {
        val a = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val f = a.optJSONObject(i) ?: return@mapNotNull null
            val c = f.optJSONArray("center") ?: return@mapNotNull null
            if (c.length() < 2) return@mapNotNull null
            Destination(f.optString("place_name").ifBlank { f.optString("text") }, LatLon(c.getDouble(1), c.getDouble(0)))
        }
    }
}
