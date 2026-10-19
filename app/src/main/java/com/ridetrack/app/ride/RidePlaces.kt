package com.ridetrack.app.ride

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.ridetrack.app.data.RideRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/** A ride's name from where it went: "13 October | Bandra to Lonavala". Pure. */
object RidePlaceNames {
    /**
     * "13 October | Bandra to Lonavala"; "13 October | Around Bandra" when it starts and ends in
     * the same place; "13 October | Bandra" when only one end is known; null when neither is.
     */
    fun name(startMillis: Long, from: String?, to: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val a = from?.trim()?.takeIf { it.isNotEmpty() }
        val b = to?.trim()?.takeIf { it.isNotEmpty() }
        val where = when {
            a != null && b != null && a.equals(b, ignoreCase = true) -> "Around $a"
            a != null && b != null -> "$a to $b"
            else -> a ?: b ?: return null
        }
        val day = DateTimeFormatter.ofPattern("d MMMM", locale).format(Instant.ofEpochMilli(startMillis).atZone(zone))
        return "$day | $where"
    }

    /** The name the app gives before places are known: it may be replaced; a name the rider typed never is. */
    fun isAuto(name: String, startMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): Boolean =
        name.isBlank() || name == RideNames.forStart(startMillis, zone, locale)
}

/**
 * Names rides by place once they're saved, with Android's own place lookup (it needs a
 * connection; a ride saved offline is named the next time the app opens).
 */
class RidePlaces(private val context: Context, private val rides: RideRepository) {
    /** Names [rideId] by place if it still has the app's own name; false when the places couldn't be found. */
    suspend fun nameRide(rideId: String): Boolean {
        val ride = rides.get(rideId) ?: return true
        if (!RidePlaceNames.isAuto(ride.name, ride.startTimeMillis)) return true
        if (!Geocoder.isPresent()) return true
        val points = withContext(Dispatchers.IO) { rides.track(rideId).samples.filter { it.latitude != null && it.longitude != null } }
        val first = points.firstOrNull() ?: return true
        val last = points.last()
        val from = place(first.latitude!!, first.longitude!!)
        val to = place(last.latitude!!, last.longitude!!)
        val name = RidePlaceNames.name(ride.startTimeMillis, from, to) ?: return false
        rides.rename(rideId, name)
        return true
    }

    /** Rides from the last two weeks still named by the app (saved offline): named now. */
    suspend fun catchUp(completed: List<com.ridetrack.telemetry.model.Ride>) {
        val since = System.currentTimeMillis() - 14 * 86_400_000L
        completed.filter { it.startTimeMillis >= since && RidePlaceNames.isAuto(it.name, it.startTimeMillis) }.forEach { r ->
            runCatching { nameRide(r.id) }
        }
    }

    private val nearby = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The area's name at a point, remembered per ~1 km so a ride's clips look it up once. */
    suspend fun placeAt(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        nearby[key]?.let { return it }
        return place(lat, lon)?.also { nearby[key] = it }
    }

    /** The state or region at a point (for the "States" badge); null when it can't be looked up. */
    suspend fun stateAt(lat: Double, lon: Double): String? = withTimeoutOrNull(10_000) {
        runCatching { address(lat, lon) }.getOrNull()?.adminArea
    }

    /** The neighbourhood or town at a point; null when it can't be looked up. */
    private suspend fun place(lat: Double, lon: Double): String? = withTimeoutOrNull(10_000) {
        val a = runCatching { address(lat, lon) }.getOrNull() ?: return@withTimeoutOrNull null
        a.subLocality ?: a.locality ?: a.subAdminArea ?: a.adminArea
    }

    private suspend fun address(lat: Double, lon: Double): Address? {
        val geo = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geo.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { geo.getFromLocation(lat, lon, 1)?.firstOrNull() }
        }
    }
}
