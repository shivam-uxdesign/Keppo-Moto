package com.ridetrack.app.ui.rides

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.ridetrack.app.sensors.Permissions
import org.maplibre.android.geometry.LatLng

/** Where the phone is, for the map: no prompts, and GPS isn't left running. */
internal object MyLocation {
    fun allowed(context: Context): Boolean = Permissions.hasFineLocation(context)

    /**
     * Calls [onFix] with the newest last-known fix straight away (if any), then once more with a
     * fresh fix when it arrives. Nothing happens without location permission or with it off.
     */
    @SuppressLint("MissingPermission")
    fun get(context: Context, onFix: (LatLng) -> Unit) {
        if (!allowed(context)) return
        val lm = context.getSystemService<LocationManager>() ?: return
        if (!LocationManagerCompat.isLocationEnabled(lm)) return
        val last = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        last?.let { onFix(it.toLatLng()) }
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        runCatching {
            LocationManagerCompat.getCurrentLocation(lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(context)) { loc ->
                loc?.let { onFix(it.toLatLng()) }
            }
        }
    }

    private fun Location.toLatLng() = LatLng(latitude, longitude)
}
