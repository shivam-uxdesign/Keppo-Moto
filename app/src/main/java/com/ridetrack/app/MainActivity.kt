package com.ridetrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.data.AppTheme
import com.ridetrack.app.ui.nav.RideTrackNavHost
import com.ridetrack.app.ui.theme.RideTrackTheme

class MainActivity : ComponentActivity() {
    /** A ride to open, from Keppo Journal's OPEN_RIDE (see docs/keppo-ride-format.md). */
    private val openRide = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            openRide.value = rideToOpen(intent)
            endRideIfAsked(intent)
        }
        val settings = (application as RideTrackApp).container.settings.settings
        setContent {
            // Wait for the stored choice (a few ms) so the app doesn't flash the wrong theme.
            val theme by settings.collectAsStateWithLifecycle(initialValue = null)
            val choice = theme?.appTheme ?: return@setContent
            val dark = when (choice) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            RideTrackTheme(dark = dark) {
                RideTrackNavHost(openRideId = openRide.value, onOpenRideHandled = { openRide.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        rideToOpen(intent)?.let { openRide.value = it }
        endRideIfAsked(intent)
    }

    /** "End ride" on the break notification: the live screen asks to confirm. */
    private fun endRideIfAsked(intent: Intent?) {
        if (intent?.action == ACTION_END_RIDE) (application as RideTrackApp).container.session.requestEnd()
    }

    private fun rideToOpen(intent: Intent?): String? =
        intent?.takeIf { it.action == ACTION_OPEN_RIDE }?.getStringExtra("rideId")?.takeIf { it.isNotBlank() }

    companion object {
        const val ACTION_OPEN_RIDE = "com.keppo.action.OPEN_RIDE"
        const val ACTION_END_RIDE = "com.ridetrack.app.END_RIDE"
    }
}
