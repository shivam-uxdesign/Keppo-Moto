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
    /** Outside India (Beta): card SMS near a fuel stop that couldn't be read, for review. */
    private val onBetaIssue: (FuelBetaIssue) -> Unit = {},
) {
    val fills: Flow<List<FuelFill>> = settings.settings.map { Fuel.decodeFills(it.fuelLog) }
    val prompts: Flow<List<FuelPrompt>> = settings.settings.map { Fuel.decodePrompts(it.fuelPrompts) }

    suspend fun add(fill: FuelFill) = settings.setFuelLog(Fuel.encodeFills(Fuel.decodeFills(settings.settings.first().fuelLog) + fill))

    /** A fill corrected by the rider (same id, new values). */
    suspend fun update(fill: FuelFill) = settings.setFuelLog(
        Fuel.encodeFills(Fuel.decodeFills(settings.settings.first().fuelLog).map { if (it.id == fill.id) fill else it }),
    )

    suspend fun delete(id: String) = settings.setFuelLog(Fuel.encodeFills(Fuel.decodeFills(settings.settings.first().fuelLog).filterNot { it.id == id }))


    /** Mileage for [bikeId] from its fills and its real rides. */
    fun mileage(bikeId: String, fills: List<FuelFill>, allRides: List<com.ridetrack.telemetry.model.Ride>): Mileage = Fuel.mileage(
        fills.filter { it.bikeId == bikeId },
        allRides.filter { it.bikeId == bikeId && it.source != DataSourceKind.DEMO }.map { it.startTimeMillis to it.stats.distanceM },
    )

    /** Stations: yours, kept on the phone for your areas, then OpenStreetMap online. */
    val stations = StationFinder(context)

    /**
     * After a ride, every way a fill-up shows itself, so one miss doesn't lose it:
     *  - a stop of 1–15 min (creeping in the queue and GPS wobble allowed) near a station;
     *  - a card payment to a fuel company (when reading SMS is on), even with no stop found;
     *  - a stop of a few minutes with no station known: kept under "Not sure" on the Fuel page.
     * Ones already asked on the spot, or that you said weren't fill-ups, aren't asked again. Why
     * a stop wasn't counted goes in the ride's log.
     */
    suspend fun checkRide(rideId: String) {
        val ride = rides.observeCompleted().first().firstOrNull { it.id == rideId } ?: return
        if (ride.source == DataSourceKind.DEMO) return markChecked(rideId)
        val samples = rides.track(rideId).samples
        val stops = PumpStops.find(samples).take(MAX_STOPS)
        val readSms = settings.settings.first().fuelReadSms && canReadSms()
        val before = Fuel.decodePrompts(settings.settings.first().fuelPrompts).filter { it.rideId == rideId }
        val handled = before.map { it.timeMillis } + declined(rideId)
        var offline = false
        val asked = ArrayList<FuelPrompt>()
        for (stop in stops) {
            val at = clock(stop.toMillis)
            val minutes = (stop.toMillis - stop.fromMillis) / 60_000.0
            if (handled.any { kotlin.math.abs(it - stop.toMillis) < SAME_STOP_MS }) continue
            val station = try {
                stations.near(stop.latitude, stop.longitude)
            } catch (e: Exception) {
                offline = true
                rideLog(rideId, "fuel: stop at $at: couldn't look up stations (no internet?); checked again later")
                continue
            }
            val sms = if (readSms) cardPayments(stop.fromMillis - SMS_BEFORE_MILLIS, stop.toMillis + SMS_AFTER_MILLIS) else emptyList()
            if (readSms && sms.isEmpty()) reportUnread(stop)
            val pay = CardSms.pick(sms, stop.toMillis)
            val guessed = if (stop.guessed) " (no GPS during the stop: used where the bike was just before)" else ""
            when {
                station != null -> {
                    rideLog(rideId, "fuel: stop at $at, ${"%.1f".format(java.util.Locale.US, minutes)} min, at ${station.name}${if (station.mine) " (you've filled up here before)" else ""}$guessed: asking")
                    asked += FuelPrompt(rideId, ride.bikeId, stop.toMillis, station.name, pay?.amount, stop.latitude, stop.longitude, sure = true, how = "stop")
                }
                pay?.fuel == true -> {
                    rideLog(rideId, "fuel: stop at $at with no station on the map, but a card payment to ${pay.merchant ?: "a fuel company"}: asking")
                    asked += FuelPrompt(rideId, ride.bikeId, stop.toMillis, pay.merchant ?: "Fuel station", pay.amount, stop.latitude, stop.longitude, sure = true, how = "sms")
                }
                stop.toMillis - stop.fromMillis >= UNSURE_MIN_MS -> {
                    rideLog(rideId, "fuel: stop at $at, ${"%.1f".format(java.util.Locale.US, minutes)} min, no station known within ${StationFinder.RADIUS_M.toInt()} m$guessed: kept under Not sure")
                    asked += FuelPrompt(rideId, ride.bikeId, stop.toMillis, "A stop with no station on the map", pay?.amount, stop.latitude, stop.longitude, sure = false, how = "stop")
                }
                else -> rideLog(rideId, "fuel: stop at $at, ${"%.1f".format(java.util.Locale.US, minutes)} min, no station known within ${StationFinder.RADIUS_M.toInt()} m$guessed: not a fill-up")
            }
        }
        // Card payments to fuel companies during the ride that no stop explains: still fill-ups.
        if (readSms) {
            val end = ride.endTimeMillis ?: samples.lastOrNull()?.timeMillis ?: ride.startTimeMillis
            cardPayments(ride.startTimeMillis - SMS_BEFORE_MILLIS, end + SMS_AFTER_MILLIS).filter { it.fuel }.forEach { pay ->
                val known = (asked.map { it.timeMillis } + handled).any { kotlin.math.abs(it - pay.timeMillis) < SAME_STOP_MS * 3 }
                if (known) return@forEach
                val where = samples.filter { it.latitude != null && it.longitude != null }.minByOrNull { kotlin.math.abs(it.timeMillis - pay.timeMillis) }
                rideLog(rideId, "fuel: card payment to ${pay.merchant ?: "a fuel company"} at ${clock(pay.timeMillis)} with no stop found: asking")
                asked += FuelPrompt(rideId, ride.bikeId, pay.timeMillis, pay.merchant ?: "Fuel station", pay.amount, where?.latitude, where?.longitude, sure = true, how = "sms")
            }
        }
        // No internet: look again later (on the next launch).
        if (!offline) markChecked(rideId)
        // With the amount from the card SMS and today's price, the fill is saved without asking (Undo in the notification).
        val found = asked.mapNotNull { p -> if (p.sure && p.amount != null && autoFill(p)) null else p }
        if (found.isNotEmpty()) {
            val now = Fuel.decodePrompts(settings.settings.first().fuelPrompts)
            settings.setFuelPrompts(Fuel.encodePrompts(now + found.filter { f -> now.none { it.rideId == f.rideId && it.timeMillis == f.timeMillis } }))
        }
        // The stations around this ride kept on the phone, for asking on the spot next time (offline).
        listOfNotNull(samples.firstOrNull { it.latitude != null }, samples.lastOrNull { it.latitude != null }).forEach { s ->
            runCatching { stations.keepArea(s.latitude!!, s.longitude!!) }
        }
    }

    /** Saves [prompt]'s fill with today's price for the place; false when no price is known (it's asked instead). */
    private suspend fun autoFill(prompt: FuelPrompt): Boolean {
        val amount = prompt.amount ?: return false
        val lat = prompt.latitude ?: return false
        val lon = prompt.longitude ?: return false
        val all = Fuel.decodeFills(settings.settings.first().fuelLog)
        // Already in the log (typed, or saved before): nothing to do.
        if (all.any { it.bikeId == prompt.bikeId && kotlin.math.abs(it.timeMillis - prompt.timeMillis) < 30 * 60_000L }) return true
        val last = all.filter { it.pricePerLitre != null }.maxByOrNull { it.timeMillis }?.pricePerLitre
        val price = prices?.let { runCatching { it.at(lat, lon, last) }.getOrNull() } ?: return false
        val fill = Fuel.newFill(prompt.bikeId, prompt.timeMillis, FuelPriceText.litres(amount, price.perLitre), amount, price.perLitre, prompt.station, if (price.exact) "auto" else "auto-about")
        add(fill)
        stations.remember(prompt.station, lat, lon)
        FuelNotice.saved(context, fill, prompt, price)
        return true
    }

    /** "Yes, I filled up": the fill saved, the prompt gone, and the station remembered. */
    suspend fun confirm(prompt: FuelPrompt, litres: Double, amount: Double?, price: Double?) {
        val source = when {
            prompt.how == "sms" || prompt.amount != null -> "sms"
            prompt.how == "live" -> "live"
            prompt.how == "button" -> "button"
            prompt.how == "marked" -> "marked"
            else -> "stop"
        }
        add(Fuel.newFill(prompt.bikeId, prompt.timeMillis, litres, amount, price, prompt.station.takeIf { prompt.sure }, source))
        dismiss(prompt, declinedToo = false)
        val lat = prompt.latitude
        val lon = prompt.longitude
        if (lat != null && lon != null) stations.remember(prompt.station.takeIf { prompt.sure } ?: "Your fuel stop", lat, lon)
    }

    /**
     * A petrol pump the rider marked on [rideId]'s map afterwards: remembered, so stops there are
     * asked about from now on. With [filled], this ride's fill-up there is asked for too, at the
     * stop nearest the pump (or the moment the ride passed closest), replacing an unsure one.
     */
    suspend fun markPump(rideId: String, name: String, lat: Double, lon: Double, filled: Boolean) {
        stations.remember(name, lat, lon)
        rideLog(rideId, "fuel: you marked a petrol pump ($name) on the map" + if (filled) ", and a fill-up here" else "")
        if (!filled) return
        val ride = rides.observeCompleted().first().firstOrNull { it.id == rideId } ?: return
        val samples = rides.track(rideId).samples
        val stop = PumpStops.find(samples).filter { Stations.metres(it.latitude, it.longitude, lat, lon) < MARK_STOP_M }
            .minByOrNull { Stations.metres(it.latitude, it.longitude, lat, lon) }
        val time = stop?.toMillis ?: samples.filter { it.latitude != null && it.longitude != null }
            .minByOrNull { Stations.metres(it.latitude!!, it.longitude!!, lat, lon) }?.timeMillis ?: return
        val now = Fuel.decodePrompts(settings.settings.first().fuelPrompts)
        val others = now.filterNot { it.rideId == rideId && kotlin.math.abs(it.timeMillis - time) < SAME_STOP_MS }
        settings.setFuelPrompts(Fuel.encodePrompts(others + FuelPrompt(rideId, ride.bikeId, time, name, null, lat, lon, sure = true, how = "marked")))
    }

    /** "Not a fill-up": the prompt goes and that stop isn't asked again. */
    suspend fun dismiss(prompt: FuelPrompt) = dismiss(prompt, declinedToo = true)

    private suspend fun dismiss(prompt: FuelPrompt, declinedToo: Boolean) {
        if (declinedToo) decline(prompt.rideId, prompt.timeMillis)
        settings.setFuelPrompts(
            Fuel.encodePrompts(Fuel.decodePrompts(settings.settings.first().fuelPrompts).filterNot { it.rideId == prompt.rideId && it.timeMillis == prompt.timeMillis }),
        )
    }

    /** Stops the rider said weren't fill-ups (on the spot or after), per ride: not asked again. */
    fun decline(rideId: String, timeMillis: Long) {
        val set = prefs.getStringSet(DECLINED, emptySet()).orEmpty()
        prefs.edit().putStringSet(DECLINED, (set + "$rideId@$timeMillis").toList().takeLast(200).toSet()).apply()
    }

    private fun declined(rideId: String): List<Long> =
        prefs.getStringSet(DECLINED, emptySet()).orEmpty().mapNotNull { e -> e.split('@').takeIf { it.size == 2 && it[0] == rideId }?.get(1)?.toLongOrNull() }

    /** A line in the ride's log (exported with the ride), so a missed fill-up can be explained. */
    private fun rideLog(rideId: String, message: String) =
        com.ridetrack.app.moments.MomentLog.append(java.io.File(java.io.File(java.io.File(context.filesDir, "moments"), rideId), com.ridetrack.app.moments.MomentLog.FILE_NAME), message)

    private fun clock(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString()

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

    /**
     * Outside India (Beta): there were messages around the stop, but no payment could be read.
     * Only their shape goes in the report ("spend+currency+number"), never their words.
     */
    @SuppressLint("MissingPermission")
    private fun reportUnread(stop: Stop) {
        val region = FuelRegion.current
        if (region.india) return
        val shapes = runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
                "${Telephony.Sms.DATE} BETWEEN ? AND ?",
                arrayOf((stop.fromMillis - SMS_BEFORE_MILLIS).toString(), (stop.toMillis + SMS_AFTER_MILLIS).toString()), null,
            )?.use { cur -> buildList { while (cur.moveToNext()) add(CardSms.shape(cur.getString(0).orEmpty(), region)) } }.orEmpty()
        }.getOrDefault(emptyList())
        // Only messages that look like money matter (not chats, not OTPs without amounts).
        val likely = shapes.filter { "number" in it && ("spend" in it || "currency" in it) }
        if (likely.isEmpty()) return
        onBetaIssue(FuelBetaIssue("Card SMS at a fuel stop not read · ${region.country} · ${region.currency} · ${likely.size} message(s): ${likely.groupingBy { it }.eachCount().entries.joinToString { "${it.key} ×${it.value}" }}"))
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
        const val DECLINED = "declined_stops"
        /** Two finds this close are the same fill-up. */
        const val SAME_STOP_MS = 10 * 60_000L
        /** A stop this close to a pump you mark is where you filled up. */
        private const val MARK_STOP_M = 300.0
        /** A stop this long with no station known is kept under "Not sure". */
        const val UNSURE_MIN_MS = 2 * 60_000L
        const val RECENT_MILLIS = 3 * 86_400_000L
        const val SMS_BEFORE_MILLIS = 15 * 60_000L
        const val SMS_AFTER_MILLIS = 30 * 60_000L
    }
}
