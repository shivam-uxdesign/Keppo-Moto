package com.ridetrack.app.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.telemetry.model.DataSourceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The fuel log: fill-ups per bike (kept in settings, so they're backed up), petrol pump stops
 * waiting for "Filled up here?", and finding them after each ride.
 */
class FuelRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rides: RideRepository,
    /** Today's petrol price where the rider filled up; null = not looked up (no Gemini). */
    private val prices: FuelPrices? = null,
) {
    val fills: Flow<List<FuelFill>> = settings.settings.map { Fuel.decodeFills(it.fuelLog) }
    val prompts: Flow<List<FuelPrompt>> = settings.settings.map { Fuel.decodePrompts(it.fuelPrompts) }

    suspend fun add(fill: FuelFill) = settings.setFuelLog(Fuel.encodeFills(Fuel.decodeFills(settings.settings.first().fuelLog) + fill))

    suspend fun delete(id: String) = settings.setFuelLog(Fuel.encodeFills(Fuel.decodeFills(settings.settings.first().fuelLog).filterNot { it.id == id }))

    suspend fun dismiss(prompt: FuelPrompt) = settings.setFuelPrompts(
        Fuel.encodePrompts(Fuel.decodePrompts(settings.settings.first().fuelPrompts).filterNot { it.rideId == prompt.rideId && it.timeMillis == prompt.timeMillis }),
    )

    /** Mileage for [bikeId] from its fills and its real rides. */
    fun mileage(bikeId: String, fills: List<FuelFill>, allRides: List<com.ridetrack.telemetry.model.Ride>): Mileage = Fuel.mileage(
        fills.filter { it.bikeId == bikeId },
        allRides.filter { it.bikeId == bikeId && it.source != DataSourceKind.DEMO }.map { it.startTimeMillis to it.stats.distanceM },
    )

    /**
     * After a ride: its stops of 1–15 min near a petrol pump (OpenStreetMap; only each stop's
     * position is sent) become "Filled up here?" on Home, with the card SMS amount when that's on.
     */
    suspend fun checkRide(rideId: String) {
        val ride = rides.observeCompleted().first().firstOrNull { it.id == rideId } ?: return
        if (ride.source == DataSourceKind.DEMO) return markChecked(rideId)
        val stops = PumpStops.find(rides.track(rideId).samples).take(MAX_STOPS)
        if (stops.isEmpty()) return markChecked(rideId)
        val readSms = settings.settings.first().fuelReadSms && canReadSms()
        var offline = false
        val asked = stops.mapNotNull { stop ->
            val station = runCatching { nearestPump(stop.latitude, stop.longitude) }.onFailure { offline = true }.getOrNull() ?: return@mapNotNull null
            val sms = if (readSms) cardPayments(stop.fromMillis - SMS_BEFORE_MILLIS, stop.toMillis + SMS_AFTER_MILLIS) else emptyList()
            stop to FuelPrompt(rideId, ride.bikeId, stop.toMillis, station, CardSms.pick(sms, stop.toMillis)?.amount)
        }
        // No internet: look again later (on the next launch).
        if (!offline) markChecked(rideId)
        // With the amount from the card SMS and today's price, the fill is saved without asking (Undo in the notification).
        val found = asked.mapNotNull { (stop, prompt) -> if (prompt.amount != null && autoFill(prompt, stop)) null else prompt }
        if (found.isEmpty()) return
        val now = Fuel.decodePrompts(settings.settings.first().fuelPrompts)
        settings.setFuelPrompts(Fuel.encodePrompts(now + found.filter { f -> now.none { it.rideId == f.rideId && it.timeMillis == f.timeMillis } }))
    }

    /** Saves [prompt]'s fill with today's price for the place; false when no price is known (it's asked instead). */
    private suspend fun autoFill(prompt: FuelPrompt, stop: Stop): Boolean {
        val amount = prompt.amount ?: return false
        val all = Fuel.decodeFills(settings.settings.first().fuelLog)
        // Already in the log (typed, or saved before): nothing to do.
        if (all.any { it.bikeId == prompt.bikeId && kotlin.math.abs(it.timeMillis - prompt.timeMillis) < 30 * 60_000L }) return true
        val last = all.filter { it.pricePerLitre != null }.maxByOrNull { it.timeMillis }?.pricePerLitre
        val price = prices?.let { runCatching { it.at(stop.latitude, stop.longitude, last) }.getOrNull() } ?: return false
        val fill = Fuel.newFill(prompt.bikeId, prompt.timeMillis, FuelPriceText.litres(amount, price.perLitre), amount, price.perLitre, prompt.station, if (price.exact) "auto" else "auto-about")
        add(fill)
        FuelNotice.saved(context, fill, prompt, price)
        return true
    }

    /** Undo from the notification: the fill goes, and "Filled up here?" comes back to correct it by hand. */
    suspend fun undoAuto(fillId: String, prompt: FuelPrompt) {
        delete(fillId)
        val now = Fuel.decodePrompts(settings.settings.first().fuelPrompts)
        if (now.none { it.rideId == prompt.rideId && it.timeMillis == prompt.timeMillis }) settings.setFuelPrompts(Fuel.encodePrompts(now + prompt))
    }

    /** Rides of the last few days not yet checked for pump stops (e.g. saved while offline). */
    suspend fun checkRecent() {
        val since = System.currentTimeMillis() - RECENT_MILLIS
        rides.observeCompleted().first()
            .filter { it.startTimeMillis > since && it.id !in checked() }
            .forEach { runCatching { checkRide(it.id) } }
    }

    private val prefs = context.getSharedPreferences("fuel", Context.MODE_PRIVATE)
    private fun checked(): Set<String> = prefs.getStringSet(CHECKED, emptySet()).orEmpty()
    private fun markChecked(rideId: String) {
        prefs.edit().putStringSet(CHECKED, (checked() + rideId).toList().takeLast(200).toSet()).apply()
    }

    fun canReadSms() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The pump's name within ~70 m, from OpenStreetMap; null if none. */
    private fun nearestPump(lat: Double, lon: Double): String? {
        val q = "[out:json][timeout:15];nwr[\"amenity\"=\"fuel\"](around:70,$lat,$lon);out center 1;"
        val c = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "KeppoMoto/1 (Android)")
            c.outputStream.use { it.write("data=${URLEncoder.encode(q, "UTF-8")}".toByteArray()) }
            if (c.responseCode !in 200..299) return null
            val el = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("elements")?.optJSONObject(0) ?: return null
            val tags = el.optJSONObject("tags")
            return listOf("name", "brand", "operator").firstNotNullOfOrNull { k -> tags?.optString(k)?.takeIf { it.isNotBlank() } } ?: "Petrol pump"
        } finally {
            c.disconnect()
        }
    }

    /** Card payments in SMS received between [from] and [to] (needs the rider's permission). */
    @SuppressLint("MissingPermission")
    private fun cardPayments(from: Long, to: Long): List<CardPayment> {
        if (!canReadSms()) return emptyList()
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cur ->
                buildList {
                    while (cur.moveToNext()) CardSms.parse(cur.getString(0).orEmpty(), cur.getLong(1))?.let(::add)
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_STOPS = 6
        const val CHECKED = "checked_rides"
        const val RECENT_MILLIS = 3 * 86_400_000L
        const val SMS_BEFORE_MILLIS = 15 * 60_000L
        const val SMS_AFTER_MILLIS = 30 * 60_000L
    }
}
