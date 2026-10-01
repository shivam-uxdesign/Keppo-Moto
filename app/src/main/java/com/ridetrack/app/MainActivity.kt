package com.ridetrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.data.AppTheme
import com.ridetrack.app.ui.nav.RideTrackNavHost
import com.ridetrack.app.ui.theme.RideTrackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
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
                RideTrackNavHost()
            }
        }
    }
}
