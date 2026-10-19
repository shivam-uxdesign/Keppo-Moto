package com.ridetrack.app.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ridetrack.app.MainActivity
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.ride.QuickStart

/** Quick Settings "Start ride": starts a ride (via the app's countdown), or opens the ride in progress. */
class StartRideTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val riding = (application as RideTrackApp).container.session.state.value.isActive
        qsTile?.apply {
            state = if (riding) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = if (riding) "Riding" else null
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val riding = (application as RideTrackApp).container.session.state.value.isActive
        val intent = if (riding) {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            QuickStart.intent(this)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 44, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
