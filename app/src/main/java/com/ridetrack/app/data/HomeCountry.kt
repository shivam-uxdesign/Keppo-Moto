package com.ridetrack.app.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** The rider's country, as an ISO code ("IN", "GB"): the SIM's, else the network's, else the phone's language region; "" if none. */
object HomeCountry {
    fun of(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(runCatching { tm?.simCountryIso }.getOrNull(), runCatching { tm?.networkCountryIso }.getOrNull(), Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT).orEmpty()
    }
}
