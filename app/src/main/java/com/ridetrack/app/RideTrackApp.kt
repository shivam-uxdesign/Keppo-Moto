package com.ridetrack.app

import com.ridetrack.app.ride.RideContinuation
import com.ridetrack.app.trash.PurgeWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.ridetrack.app.backup.BackupWorker
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import org.maplibre.android.MapLibre

class RideTrackApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        keepCrashTrace()
        container = AppContainer(this)
        MapLibre.getInstance(this)
        createNotificationChannel()
        container.hud.start()
        container.crashAlerts.start()
        PurgeWorker.schedule(this)
        container.appScope.launch { container.trash.purgeExpired() }
        // Why the app last stopped, into the log of the ride it interrupted.
        container.appScope.launch {
            val prefs = getSharedPreferences(STATE_PREFS_NAME, MODE_PRIVATE)
            val ride = container.continuation.latest()?.first?.id
            container.continuation.reportExit(prefs.getLong(EXIT_REPORTED_KEY, 0L), ride)?.let { prefs.edit().putLong(EXIT_REPORTED_KEY, it).apply() }
        }
        container.appScope.launch {
            val b = container.settings.settings.first().backup
            if (b.connected) BackupWorker.scheduleDaily(this@RideTrackApp, b.allowMobileData)
        }
    }

    /** An uncaught crash's stack trace, kept for the ride log on the next launch. */
    private fun keepCrashTrace() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                java.io.File(filesDir, RideContinuation.CRASH_FILE).writeText("${thread.name}: ${e.stackTraceToString().take(4_000)}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            RIDE_CHANNEL_ID,
            getString(R.string.notification_channel_ride),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_ride_desc)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
        // Breaks: a heads-up the rider sees once, with a short buzz and no sound.
        val alerts = NotificationChannel(
            RIDE_ALERTS_CHANNEL_ID,
            getString(R.string.notification_channel_ride_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notification_channel_ride_alerts_desc)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120)
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(alerts)
    }

    companion object {
        const val RIDE_CHANNEL_ID = "ride_recording"
        const val RIDE_ALERTS_CHANNEL_ID = "ride_alerts"
        private const val STATE_PREFS_NAME = "app_state"
        private const val EXIT_REPORTED_KEY = "exit_reported_millis"
    }
}
