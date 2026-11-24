package com.ridetrack.app.keepmoving

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Places the rider rode to with Keep moving, saved when they tap Start (24 Nov), newest first, at
 * most [MAX]. Google's terms: a place's ID may be kept, its location only [GOOGLE_KEEP_MS], its
 * name never; so a place found with Google's search keeps its ID and the phone's own area name,
 * and its location is dropped after 30 days and fetched again (by ID) when it's next used.
 * Dropped pins and ride ends are the rider's own data and are kept as they are.
 */
class SavedPlaces(context: Context) {
    data class Place(
        val id: String,
        val label: String,
        val at: LatLon?,
        val placeId: String?,
        val locatedAt: Long,
        val usedAt: Long,
        val uses: Int,
    ) {
        fun destination(): Destination? = at?.let { Destination(label, it, placeId = placeId) }
    }

    private val prefs = context.getSharedPreferences("keep-moving-places", Context.MODE_PRIVATE)
    private val _places = MutableStateFlow(expire(read(), System.currentTimeMillis()))
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    /** [d] was ridden to: added, or the saved place within [SAME_M] used again. [label] is what's saved. */
    fun used(d: Destination, label: String, now: Long = System.currentTimeMillis()) = update(remember(_places.value, d, label, now))

    fun rename(id: String, label: String) = update(_places.value.map { if (it.id == id) it.copy(label = label.trim().take(60).ifBlank { it.label }) else it })

    fun remove(id: String) = update(_places.value.filterNot { it.id == id })

    /** A saved Google place's location fetched again by its ID. */
    fun located(id: String, at: LatLon, now: Long = System.currentTimeMillis()) =
        update(_places.value.map { if (it.id == id) it.copy(at = at, locatedAt = now) else it })

    private fun update(list: List<Place>) {
        _places.value = list
        prefs.edit().putString(KEY, JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
    }

    private fun read(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(
                o.getString("id"), o.getString("label"),
                if (o.has("lat")) LatLon(o.getDouble("lat"), o.getDouble("lon")) else null,
                o.optString("placeId").takeIf { it.isNotBlank() },
                o.optLong("locatedAt"), o.optLong("usedAt"), o.optInt("uses", 1),
            )
        }
    }.getOrDefault(emptyList())

    private fun Place.json() = JSONObject().put("id", id).put("label", label)
        .apply { at?.let { put("lat", it.lat).put("lon", it.lon) } }
        .put("placeId", placeId ?: "").put("locatedAt", locatedAt).put("usedAt", usedAt).put("uses", uses)

    companion object {
        const val MAX = 20
        const val SAME_M = 150.0
        const val GOOGLE_KEEP_MS = 30L * 24 * 3_600_000L
        private const val KEY = "places"

        /** [list] with [d] remembered: the same place (within [SAME_M], or the same Google ID) used again, else added; newest first, [MAX] kept. */
        fun remember(list: List<Place>, d: Destination, label: String, now: Long): List<Place> {
            val same = list.firstOrNull { p ->
                (d.placeId != null && p.placeId == d.placeId) || (p.at != null && TrafficRoutes.distanceM(p.at, d.at) < SAME_M)
            }
            val updated = if (same != null) {
                same.copy(at = d.at, locatedAt = now, usedAt = now, uses = same.uses + 1, placeId = d.placeId ?: same.placeId)
            } else {
                Place(UUID.randomUUID().toString(), label, d.at, d.placeId, now, now, 1)
            }
            return (listOf(updated) + list.filterNot { it.id == updated.id }).sortedByDescending { it.usedAt }.take(MAX)
        }

        /** Google places' locations dropped after [GOOGLE_KEEP_MS] (their ID and label stay). */
        fun expire(list: List<Place>, now: Long): List<Place> =
            list.map { if (it.placeId != null && it.at != null && now - it.locatedAt > GOOGLE_KEEP_MS) it.copy(at = null) else it }
    }
}
