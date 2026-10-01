package com.ridetrack.app

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
