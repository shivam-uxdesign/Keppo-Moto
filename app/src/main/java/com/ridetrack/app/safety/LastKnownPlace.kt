package com.ridetrack.app.safety

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.ridetrack.app.sensors.Permissions

/** Where the phone last knew it was, from any source, and how long ago (for an alert with no GPS fix yet). */
data class LastKnownPlace(val latitude: Double, val longitude: Double, val accuracyM: Double?, val ageMillis: Long) {
    companion object {
        /** The newest last-known location of any provider, if it's under 6 hours old. */
        @SuppressLint("MissingPermission")
        fun of(context: Context): LastKnownPlace? {
            if (!Permissions.hasFineLocation(context)) return null
            val lm = context.getSystemService<LocationManager>() ?: return null
            val newest = runCatching { lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() } }
                .getOrDefault(emptyList())
                .maxByOrNull { it.elapsedRealtimeNanos } ?: return null
            val age = (SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos) / 1_000_000L
            if (age > MAX_AGE_MILLIS) return null
            return LastKnownPlace(newest.latitude, newest.longitude, if (newest.hasAccuracy()) newest.accuracy.toDouble() else null, age.coerceAtLeast(0))
        }

        private const val MAX_AGE_MILLIS = 6 * 60 * 60_000L
    }
}
