package com.ridetrack.app

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
        container = AppContainer(this)
        MapLibre.getInstance(this)
        createNotificationChannel()
        container.hud.start()
        container.crashAlerts.start()
        PurgeWorker.schedule(this)
        container.appScope.launch { container.trash.purgeExpired() }
        container.appScope.launch {
            val b = container.settings.settings.first().backup
            if (b.connected) BackupWorker.scheduleDaily(this@RideTrackApp, b.allowMobileData)
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
    }

    companion object {
        const val RIDE_CHANNEL_ID = "ride_recording"
    }
}
