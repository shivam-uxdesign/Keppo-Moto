package com.ridetrack.app.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ridetrack.app.RideTrackApp

/** The crash alert notification's buttons: I'm OK (cancel) and Send now. Not exported. */
class CrashAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alerts = (context.applicationContext as RideTrackApp).container.crashAlerts
        when (intent.action) {
            ACTION_OK -> alerts.cancel()
            ACTION_SEND -> alerts.sendNow()
        }
    }

    companion object {
        const val ACTION_OK = "com.keppo.moto.crash.OK"
        const val ACTION_SEND = "com.keppo.moto.crash.SEND"
    }
}
