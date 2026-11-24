package com.ridetrack.app.keepmoving

import android.content.Context
import com.ridetrack.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * Keep moving: finding where to ride with Google's place search (Places API (New)), the same
 * places Google Maps knows; MapTiler's search ([Destinations.search]) when Google can't answer.
 *
 * Costs: suggestions while typing are free when the search ends in a pick (one [session]);
 * the pick asks only for the place's location (Place Details Essentials, 10,000 free a month).
 *
 * Google's terms: a place's name and address must not be saved (3.2.3(a)(iii)), its location
 * only for 30 days, its ID forever. So Google's name is only shown; what's saved under a
 * destination is the phone's own area name ([Destinations.forSaving]).
 */
object PlaceSearch {
    const val AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete"
    fun detailsUrl(placeId: String, session: String) =
        "https://places.googleapis.com/v1/places/${URLEncoder.encode(placeId, "UTF-8")}?sessionToken=${URLEncoder.encode(session, "UTF-8")}"
    /** Only the location: the cheapest Place Details tier. */
    const val DETAILS_FIELDS = "location"
    /** Places near you first: within this of [near]. */
    private const val BIAS_RADIUS_M = 50_000.0

    /** One search, from first letter to pick: suggestions in it are billed with the pick. */
    fun newSession(): String = UUID.randomUUID().toString()

    fun available(key: String = BuildConfig.GOOGLE_ROUTES_KEY) = key.isNotBlank()

    /** The autocomplete request: India, English, places near [near] first. */
    fun request(input: String, session: String, near: LatLon?): JSONObject = JSONObject()
        .put("input", input)
        .put("sessionToken", session)
        .put("includedRegionCodes", JSONArray().put("in"))
        .put("languageCode", "en")
        .apply {
            near?.let {
                put("locationBias", JSONObject().put("circle", JSONObject().put("center", it.json()).put("radius", BIAS_RADIUS_M)))
                put("origin", it.json())
            }
        }

    /** Google's suggestions → places to show (no location yet: that's fetched on a pick). */
    fun parseSuggestions(body: String): List<Suggestion> {
        val a = JSONObject(body).optJSONArray("suggestions") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONObject(i)?.optJSONObject("placePrediction") ?: return@mapNotNull null
            val id = p.optString("placeId").ifBlank { return@mapNotNull null }
            val f = p.optJSONObject("structuredFormat")
            val main = f?.optJSONObject("mainText")?.optString("text").orEmpty()
            val secondary = f?.optJSONObject("secondaryText")?.optString("text")?.takeIf { it.isNotBlank() }
            val full = p.optJSONObject("text")?.optString("text").orEmpty()
            Suggestion(id, main.ifBlank { full }.ifBlank { return@mapNotNull null }, secondary)
        }
    }

    /** Place Details → the place's location, or null. */
    fun parseLocation(body: String): LatLon? = runCatching {
        val l = JSONObject(body).optJSONObject("location") ?: return null
        if (!l.has("latitude") || !l.has("longitude")) return null
        LatLon(l.getDouble("latitude"), l.getDouble("longitude"))
    }.getOrNull()

    /** Google's suggestions for [input]; null when Google couldn't answer (so MapTiler is asked). */
    suspend fun suggest(context: Context, input: String, session: String, near: LatLon?, key: String = BuildConfig.GOOGLE_ROUTES_KEY): List<Suggestion>? = withContext(Dispatchers.IO) {
        if (!available(key)) return@withContext null
        val (status, body) = GoogleApi.call(context, key, AUTOCOMPLETE_URL, request(input.trim(), session, near).toString(), null)
        if (status == 200) runCatching { parseSuggestions(body) }.getOrNull() else null
    }

    /** Where [s] is (ends the [session]); null when Google couldn't answer. */
    suspend fun locate(context: Context, s: Suggestion, session: String, key: String = BuildConfig.GOOGLE_ROUTES_KEY): LatLon? =
        locateId(context, s.placeId, session, key)

    /** Where the place [placeId] is (a saved place whose kept location is over 30 days old). */
    suspend fun locateId(context: Context, placeId: String, session: String = newSession(), key: String = BuildConfig.GOOGLE_ROUTES_KEY): LatLon? = withContext(Dispatchers.IO) {
        val (status, body) = GoogleApi.call(context, key, detailsUrl(placeId, session), null, DETAILS_FIELDS)
        if (status == 200) parseLocation(body) else null
    }
}

/** One of Google's suggestions: its name and area, shown while choosing. */
data class Suggestion(val placeId: String, val name: String, val area: String?)
