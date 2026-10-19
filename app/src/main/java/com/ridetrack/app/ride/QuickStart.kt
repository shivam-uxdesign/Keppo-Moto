package com.ridetrack.app.ride

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ridetrack.app.AppContainer
import com.ridetrack.app.MainActivity
import com.ridetrack.telemetry.model.Bike

/**
 * Starting a ride from outside the app: the home-screen widget, the app-icon shortcut, the Quick
 * Settings tile and "Hey Google, start a ride on Keppo Moto". Each opens the app, which counts down
 * 3 s (with Cancel) and starts on the selected bike, the same way as the Start slider on Home.
 */
object QuickStart {
    const val ACTION = "com.keppo.action.START_RIDE"
    /** The link Google's App Actions open (shortcuts.xml). */
    const val LINK = "keppomoto://ride/start"

    fun isAsk(intent: Intent?): Boolean =
        intent?.action == ACTION || (intent?.action == Intent.ACTION_VIEW && intent.data?.let { it.scheme == "keppomoto" && it.host == "ride" && it.path == "/start" } == true)

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .setData(Uri.parse(LINK))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pendingIntent(context: Context, requestCode: Int = 41): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The bike a quick start rides: the selected one, else the first. */
    fun bike(garage: List<Bike>, selectedId: String?): Bike? = garage.firstOrNull { it.id == selectedId } ?: garage.firstOrNull()

    /** Starts recording on [bike], as Home's slider does. True once a ride is running. */
    suspend fun start(c: AppContainer, bike: Bike): Boolean {
        if (c.session.state.value.isActive) return true
        c.settings.setSelectedBike(bike.id)
        // A new ride: anything left unfinished is saved as it is.
        c.continuation.saveStale(0L)
        val id = c.session.startNow(bike)
        return id != null || c.session.state.value.isActive
    }
}
