package com.ridetrack.app.keepmoving

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keep moving, step 0: Google's Routes API asked for the route to a destination with live traffic
 * (two-wheeler where Google has it). This file is only the request and the reading of the answer;
 * [TrafficLogger] sends it during a ride.
 */
object TrafficRoutes {
    const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** Only what the log needs (Google bills by what's asked for). */
    const val FIELD_MASK = "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline," +
        "routes.travelAdvisory.speedReadingIntervals,routes.description,routes.routeToken"

    const val TWO_WHEELER = "TWO_WHEELER"
    const val DRIVE = "DRIVE"

    /** The request body: from [from] (facing [headingDeg] when known) to [to], traffic on the line, up to 3 routes. */
    fun request(from: LatLon, to: LatLon, mode: String, headingDeg: Double? = null, via: List<LatLon> = emptyList()): JSONObject = JSONObject()
        .put("origin", JSONObject().put("location", JSONObject().put("latLng", from.json()).apply {
            // Which way the bike faces: Google won't start the route with a U-turn.
            headingDeg?.let { put("heading", ((it % 360 + 360) % 360).roundToInt()) }
        }))
        .put("destination", JSONObject().put("location", JSONObject().put("latLng", to.json())))
        .put("travelMode", mode)
        .put("routingPreference", "TRAFFIC_AWARE")
        .put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
        // Detour points (Keep moving's search): passed in order, as stops (route tokens don't allow
        // pass-through "via" points). Google gives no alternatives for a route with stops.
        .apply { if (via.isNotEmpty()) put("intermediates", JSONArray().apply { via.forEach { put(JSONObject().put("location", JSONObject().put("latLng", it.json()))) } }) }
        .put("computeAlternativeRoutes", via.isEmpty())
        .put("languageCode", "en-IN")
        .put("units", "METRIC")

    /** Google's answer, read; empty when it has no route. */
    fun parse(body: String): List<TrafficRoute> {
        val routes = JSONObject(body).optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i ->
            val r = routes.optJSONObject(i) ?: return@mapNotNull null
            val line = r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            val points = decodePolyline(line)
            val intervals = r.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    val o = a.optJSONObject(j) ?: return@mapNotNull null
                    // A start of 0 is left out of Google's answer.
                    SpeedInterval(o.optInt("startPolylinePointIndex", 0), o.optInt("endPolylinePointIndex", 0), Speed.of(o.optString("speed")))
                }
            }.orEmpty()
            TrafficRoute(
                durationSec = seconds(r.optString("duration")),
                freeFlowSec = seconds(r.optString("staticDuration")),
                distanceM = r.optInt("distanceMeters", 0),
                description = r.optString("description").takeIf { it.isNotBlank() },
                polyline = line,
                slowM = metres(points, intervals, Speed.SLOW),
                jamM = metres(points, intervals, Speed.JAM),
                intervals = intervals,
                // What Google's Navigation SDK follows to guide along exactly this route.
                routeToken = r.optString("routeToken").takeIf { it.isNotBlank() },
            )
        }
    }

    /** The answer says this travel mode isn't offered here (two-wheeler outside the countries that have it). */
    fun modeNotOffered(status: Int, body: String): Boolean =
        status == 400 && Regex("travel ?mode|TWO_WHEELER|not supported", RegexOption.IGNORE_CASE).containsMatchIn(body)

    /** "1234s" → 1234. */
    fun seconds(s: String): Int = s.removeSuffix("s").toDoubleOrNull()?.roundToInt() ?: 0

    /** How many metres of [points] the [intervals] of [speed] cover. */
    fun metres(points: List<LatLon>, intervals: List<SpeedInterval>, speed: Speed): Int =
        intervals.filter { it.speed == speed }.sumOf { iv ->
            (iv.start until iv.end.coerceAtMost(points.size - 1)).sumOf { k -> distanceM(points[k], points[k + 1]) }
        }.roundToInt()

    /** Google's encoded polyline → points (the standard 5-decimal format). */
    fun decodePolyline(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0
        var lon = 0
        fun next(): Int {
            var shift = 0
            var result = 0
            while (i < encoded.length) {
                val b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += next()
            lon += next()
            out += LatLon(lat / 1e5, lon / 1e5)
        }
        return out
    }

    /** Straight-line metres between two points. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

data class LatLon(val lat: Double, val lon: Double) {
    fun json(): JSONObject = JSONObject().put("latitude", lat).put("longitude", lon)
}

enum class Speed(val code: String) {
    NORMAL("NORMAL"), SLOW("SLOW"), JAM("TRAFFIC_JAM"), UNKNOWN("");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Points [start] to [end] of the route's line move at [speed]. */
data class SpeedInterval(val start: Int, val end: Int, val speed: Speed)

/** One route Google offered: how long with today's traffic and without any, and where it's slow. */
data class TrafficRoute(
    val durationSec: Int,
    val freeFlowSec: Int,
    val distanceM: Int,
    val description: String?,
    val polyline: String,
    val slowM: Int,
    val jamM: Int,
    val intervals: List<SpeedInterval>,
    /** For Google's Navigation SDK (Keep moving's directions); not saved in the log. */
    val routeToken: String? = null,
) {
    /** Minutes traffic adds to this route right now. */
    val delayMin: Int get() = ((durationSec - freeFlowSec).coerceAtLeast(0) / 60.0).roundToInt()

    fun json(): JSONObject = JSONObject()
        .put("durationSec", durationSec).put("freeFlowSec", freeFlowSec).put("distanceM", distanceM)
        .put("slowM", slowM).put("jamM", jamM).put("via", description ?: JSONObject.NULL)
        .put("intervals", JSONArray().apply { intervals.filter { it.speed != Speed.NORMAL }.forEach { put(JSONArray().put(it.start).put(it.end).put(it.speed.code)) } })
        .put("polyline", polyline)
}
