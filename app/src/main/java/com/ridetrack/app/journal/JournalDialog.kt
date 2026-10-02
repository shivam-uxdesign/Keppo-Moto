package com.ridetrack.app.journal

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.data.AppTheme
import com.ridetrack.app.ui.components.KeppoWordmark
import com.ridetrack.app.ui.theme.RideTrackTheme
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens

/**
 * A Keppo card over a dim scrim, drawn over the app that asked (Keppo Journal), in the
 * rider's chosen theme. Tapping the scrim calls [onDismiss]; taps on the card don't.
 */
fun ComponentActivity.setKeppoDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val container = (application as RideTrackApp).container
    setContent {
        val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
        val dark = when (settings?.appTheme) {
            AppTheme.DARK -> true
            AppTheme.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        RideTrackTheme(dark = dark) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(RtDimens.lg)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .background(RtColors.Surface, RoundedCornerShape(RtDimens.heroRadius))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(RtDimens.lg),
                    verticalArrangement = Arrangement.spacedBy(RtDimens.xs),
                ) {
                    KeppoWordmark(height = 14.dp)
                    Spacer(Modifier.height(RtDimens.xs))
                    content()
                }
            }
        }
    }
}
