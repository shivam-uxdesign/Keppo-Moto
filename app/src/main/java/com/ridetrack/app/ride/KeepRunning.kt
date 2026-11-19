package com.ridetrack.app.ride

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Android's battery saving for Keppo Moto. While it's on, the phone may close the app mid-ride
 * (the recording, the pop-up and Moments stop until Android restarts it), which riders saw as the
 * pop-up disappearing. Exempting the app is what navigation and tracking apps ask for.
 */
object KeepRunning {
    /** Free to run in the background: not battery-optimised and not set to "Restricted". */
    fun allowed(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return true
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService<ActivityManager>()?.isBackgroundRestricted == true
        return pm.isIgnoringBatteryOptimizations(context.packageName) && !restricted
    }

    /** Android's "Let app always run in background?" question; its settings list if that can't open. */
    @SuppressLint("BatteryLife") // A ride tracker that must not be closed mid-ride: an allowed use.
    fun request(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.getSystemService<PowerManager>()
        val needsAsk = pm?.isIgnoringBatteryOptimizations(context.packageName) == false
        if (needsAsk && runCatching { context.startActivity(ask) }.isSuccess) return
        // Exempt already but "Restricted" (or the question can't open): the app's own page holds the battery choice.
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(app) }.isSuccess) return
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
