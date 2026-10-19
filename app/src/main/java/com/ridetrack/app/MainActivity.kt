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
import kotlinx.coroutines.launch
import com.ridetrack.app.data.AppTheme
import com.ridetrack.app.ui.nav.RideTrackNavHost
import com.ridetrack.app.ui.theme.RideTrackTheme

class MainActivity : ComponentActivity() {
    /** A ride to open, from Keppo Journal's OPEN_RIDE (see docs/keppo-ride-format.md). */
    private val openRide = mutableStateOf<String?>(null)
    /** Asked to start a ride from the widget, shortcut, tile or the Assistant. */
    private val quickStart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            openRide.value = rideToOpen(intent)
            endRideIfAsked(intent)
            if (com.ridetrack.app.ride.QuickStart.isAsk(intent)) quickStart.value = true
        }
        val settings = (application as RideTrackApp).container.settings.settings
        val bikes = (application as RideTrackApp).container.bikes.observeBikes()
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
            val garage by bikes.collectAsStateWithLifecycle(initialValue = emptyList())
            val accent = if (theme?.matchAppToBike != false) com.ridetrack.app.ui.common.BikeColors.appAccent(garage, theme?.selectedBikeId) else null
            RideTrackTheme(dark = dark, accent = accent) {
                RideTrackNavHost(
                    openRideId = openRide.value,
                    onOpenRideHandled = { openRide.value = null },
                    quickStart = quickStart.value,
                    onQuickStartHandled = { quickStart.value = false },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A purchase, renewal or cancellation may have happened outside the app.
        val c = (application as RideTrackApp).container
        c.appScope.launch { c.billing.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        rideToOpen(intent)?.let { openRide.value = it }
        endRideIfAsked(intent)
        if (com.ridetrack.app.ride.QuickStart.isAsk(intent)) quickStart.value = true
    }

    /** "End ride" on the break notification: the live screen asks to confirm. */
    private fun endRideIfAsked(intent: Intent?) {
        if (intent?.action == ACTION_END_RIDE) (application as RideTrackApp).container.session.requestEnd()
    }

    /**
     * The ride to open; "studio:<id>" opens its Studio (from the suggestions notification), and
     * "studio:<id>#<reel>" one of its Reels ("studio:#<reel>" for one made from phone videos).
     */
    private fun rideToOpen(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_RIDE) return null
        intent.getStringExtra(EXTRA_REVIEW)?.let { return REVIEW_PREFIX + it }
        val ride = intent.getStringExtra("rideId").orEmpty()
        val reel = intent.getStringExtra(EXTRA_REEL)
        if (reel != null) return STUDIO_PREFIX + ride + "#" + reel
        return ride.takeIf { it.isNotBlank() }?.let { if (intent.getBooleanExtra(EXTRA_STUDIO, false)) STUDIO_PREFIX + it else it }
    }

    companion object {
        const val ACTION_OPEN_RIDE = "com.keppo.action.OPEN_RIDE"
        const val ACTION_END_RIDE = "com.ridetrack.app.END_RIDE"
        const val EXTRA_STUDIO = "studio"
        const val STUDIO_PREFIX = "studio:"
        const val EXTRA_REEL = "reelId"
        /** Opens ready videos full screen, at this one ("Today's post"). */
        const val EXTRA_REVIEW = "review"
        const val REVIEW_PREFIX = "review:"
    }
}
