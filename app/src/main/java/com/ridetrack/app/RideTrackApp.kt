package com.ridetrack.app

import com.ridetrack.app.ride.RideContinuation
import com.ridetrack.app.transcribe.AppCheckSetup
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
        AppCheckSetup.install(this)
        com.ridetrack.app.transcribe.GeminiQuota.init(this)
        com.ridetrack.app.transcribe.GeminiNet.appContext = this
        container = AppContainer(this)
        // A crash is written to the error log (with its stack trace) before the app closes.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { container.errors.record("Crash", "The app stopped (${e.javaClass.simpleName}) on ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
        MapLibre.getInstance(this)
        createNotificationChannel()
        container.hud.start()
        container.crashReports.start()
        container.errors.report = container.crashReports::record
        container.crashAlerts.start()
        container.billing.start()
        val country = com.ridetrack.app.data.HomeCountry.of(this)
        com.ridetrack.app.fuel.FuelRegion.current = com.ridetrack.app.fuel.FuelRegion.of(country)
        com.ridetrack.app.ui.format.Units.country = country
        com.ridetrack.app.ui.format.Units.system = com.ridetrack.app.ui.format.Units.defaultFor(country)
        // The rider's choice, now and when it changes (screens re-read it as settings change).
        container.appScope.launch {
            container.settings.settings.collect { s ->
                com.ridetrack.app.ui.format.Units.system = s.units ?: com.ridetrack.app.ui.format.Units.defaultFor(country)
            }
        }
        PurgeWorker.schedule(this)
        container.appScope.launch { container.trash.purgeExpired() }
        // Rides saved offline get their place names now.
        container.appScope.launch { runCatching { container.places.catchUp(container.rides.observeCompleted().first()) } }
        // Neighbouring clips of the last two days' rides joined (before Reels are made from them).
        container.appScope.launch {
            runCatching {
                val since = System.currentTimeMillis() - 2 * 86_400_000L
                val recent = container.rides.observeCompleted().first().filter { it.startTimeMillis >= since }.map { it.id }
                val changed = container.momentMerger.catchUp(recent) { id -> container.reels.reels.value.any { it.rideId == id } }
                changed.forEach { com.ridetrack.app.studio.AfterRideWorker.schedule(this@RideTrackApp, it) }
            }
        }
        container.appScope.launch {
            container.reels.purgeExpired()
            // After the purge, so files of Reels deleted for good go too.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.ridetrack.app.studio.StudioSweeper.sweepDaily(this@RideTrackApp) } }
        }
        container.transcribeSoon()
        // Reels still waiting when the app was closed are made now.
        container.reelMaker.resume()
        // "Today's post" at the rider's usual posting time.
        com.ridetrack.app.studio.TodayPostWorker.schedule(this)
        container.appScope.launch { runCatching { container.fuel.checkRecent() } }
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
