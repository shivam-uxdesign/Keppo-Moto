package com.ridetrack.app.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.launch

/**
 * Watches the ride as it happens for riding away from a stop of 1–15 min, with the same
 * tolerance as [PumpStops]: creeping forward in the queue and GPS wobble don't end the stop,
 * and under a canopy with no fix it keeps the position from just before. Pure: fed one reading
 * at a time.
 */
class LivePumpWatch {
    private var slowSince: Long? = null
    private var fastSince: Long? = null
    private var lat: Double? = null
    private var lon: Double? = null
    private var lastFixAt = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** The stop just ridden away from, or null. */
    fun feed(timeMillis: Long, speedMps: Double?, latitude: Double?, longitude: Double?): Stop? {
        if (latitude != null && longitude != null) {
            lastLat = latitude; lastLon = longitude; lastFixAt = timeMillis
        }
        val slow = speedMps == null || speedMps < PumpStops.SLOW_MPS
        val since = slowSince
        if (slow) {
            fastSince = null
            if (since == null) {
                slowSince = timeMillis
                lat = null; lon = null
            }
            // Where the bike stands: the fix during the stop, or the one from just before.
            if (latitude != null && longitude != null) { lat = latitude; lon = longitude }
            else if (lat == null && timeMillis - lastFixAt < PumpStops.NEAR_MS) { lat = lastLat; lon = lastLon }
            return null
        }
        if (since == null) return null
        val fast = fastSince ?: timeMillis.also { fastSince = it }
        val away = lat != null && latitude != null && longitude != null && Stations.metres(lat!!, lon!!, latitude, longitude) > PumpStops.BRIEF_M
        if (timeMillis - fast < PumpStops.BRIEF_MS && !away) return null
        // Riding on for real: the stop ended when the bike started moving.
        val stopLat = lat
        val stopLon = lon
        slowSince = null
        fastSince = null
        val length = fast - since
        if (stopLat == null || stopLon == null || length < MIN_MS || length > MAX_MS) return null
        return Stop(since, fast, stopLat, stopLon)
    }

    private companion object {
        const val MIN_MS = 60_000L
        const val MAX_MS = 15 * 60_000L
    }
}

/** One tap during a ride: "Filled up", from the pop-up or the ride notification. */
object FilledUp {
    fun now(context: Context) {
        val c = (context.applicationContext as RideTrackApp).container
        val ride = c.session.active.value ?: return
        val f = c.session.frame.value
        c.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { c.fuel.filledNow(ride.rideId, ride.bikeId, f?.timeMillis ?: System.currentTimeMillis(), f?.latitude, f?.longitude) }
        }
    }
}

/** "Filled up at <station>?" as you ride away from it, with Yes and No. */
object LivePumpNotice {
    const val ACTION_YES = "com.keppo.moto.FUEL_LIVE_YES"
    const val ACTION_NO = "com.keppo.moto.FUEL_LIVE_NO"

    @SuppressLint("MissingPermission")
    fun ask(context: Context, prompt: FuelPrompt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val id = ("fuel-live-" + prompt.rideId + prompt.timeMillis).hashCode()
        fun action(what: String, code: Int) = PendingIntent.getBroadcast(
            context, id + code,
            Intent(context, LivePumpReceiver::class.java).setAction(what)
                .putExtra("prompt", Fuel.encodePrompts(listOf(prompt))).putExtra("notification", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, RideTrackApp.RIDE_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Filled up at ${prompt.station}?")
            .setContentText("Yes keeps it for the amount after the ride")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Yes", action(ACTION_YES, 1))
            .addAction(0, "No", action(ACTION_NO, 2))
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** Unanswered, it goes; the stop is asked about after the ride instead. */
    private const val TIMEOUT_MS = 20 * 60_000L
}

/** Yes / No on "Filled up at …?". */
class LivePumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prompt = Fuel.decodePrompts(intent.getStringExtra("prompt")).firstOrNull() ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notification", 0))
        val c = (context.applicationContext as RideTrackApp).container
        val done = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    LivePumpNotice.ACTION_YES -> c.fuel.liveYes(prompt)
                    LivePumpNotice.ACTION_NO -> c.fuel.liveNo(prompt)
                }
            } finally { done.finish() }
        }
    }
}
