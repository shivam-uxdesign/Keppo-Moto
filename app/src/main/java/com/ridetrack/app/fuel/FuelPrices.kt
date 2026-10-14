package com.ridetrack.app.fuel

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * Today's petrol price where the rider filled up: the city from the stop's position (the phone's
 * geocoder), then [lookup] (Gemini with Google Search). One lookup per city per day; when it
 * can't be found, the last price known is used and marked as "about".
 */
class FuelPrices(
    private val context: Context,
    private val lookup: suspend (prompt: String) -> String?,
    /** Outside India (Beta): a price that couldn't be found, for review. */
    private val onBetaIssue: (FuelBetaIssue) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE)

    suspend fun at(lat: Double, lon: Double, lastKnown: Double?): PetrolPrice? {
        val place = place(lat, lon)
        val today = LocalDate.now().toString()
        val key = "$today|${place.orEmpty()}"
        prefs.getString(key, null)?.toDoubleOrNull()?.let { return PetrolPrice(it, place.orEmpty(), exact = true) }
        val region = FuelRegion.current
        val known = lastKnown ?: prefs.getString(LAST, null)?.toDoubleOrNull()
        var reply: String? = null
        val found = place?.let { p ->
            reply = runCatching { lookup(FuelPriceText.prompt(p, today, region)) }.getOrNull()
            reply?.let { FuelPriceText.parse(it, region, known) }
        }
        // Not asked (free rider, no connection) isn't a failure to review.
        if (found == null && !region.india && reply != null) {
            // What went wrong, not where: the country and currency only.
            val why = when {
                reply!!.contains("null") -> "Gemini found no price"
                else -> "the answer couldn't be read or wasn't believable"
            }
            onBetaIssue(FuelBetaIssue("Petrol price not found: $why · ${region.country} · ${region.currency}"))
        }
        if (found != null) {
            prefs.edit().putString(key, found.toString()).putString(LAST, found.toString()).apply()
            return PetrolPrice(found, place, exact = true)
        }
        val last = lastKnown ?: prefs.getString(LAST, null)?.toDoubleOrNull() ?: return null
        return PetrolPrice(last, place.orEmpty(), exact = false)
    }

    /** "Pune, Maharashtra" for a position; null without a geocoder or an answer. */
    @Suppress("DEPRECATION")
    private suspend fun place(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(", ").ifBlank { null }
            }
        }.getOrNull()
    }

    private companion object {
        const val LAST = "last"
    }
}
