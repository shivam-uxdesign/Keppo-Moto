package com.ridetrack.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridetrack.app.ride.QuickStart
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Bike
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Starting your ride in 3…": shown when a ride is started from the widget, shortcut, tile or the
 * Assistant. Cancel stops it; without a bike or the location permission it says what's needed.
 */
@Composable
fun QuickStartDialog(onStarted: () -> Unit, onClose: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var bike by remember { mutableStateOf<Bike?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(Permissions.hasFineLocation(context)) }
    var left by remember { mutableIntStateOf(COUNTDOWN) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (c.session.state.value.isActive) {
            onStarted()
            return@LaunchedEffect
        }
        val s = c.settings.settings.first()
        demo = s.demoMode
        bike = QuickStart.bike(c.bikes.observeBikes().first(), s.selectedBikeId)
        loaded = true
    }
    val ready = loaded && bike != null && (allowed || demo)
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            left--
        }
        if (QuickStart.start(c, bike!!)) onStarted() else failed = true
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = Permissions.hasFineLocation(context)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RideDark {
            Box(Modifier.fillMaxSize().background(RtColors.LiveBackground).padding(RtDimens.lg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RtDimens.md)) {
                    when {
                        !loaded -> Unit
                        bike == null -> Message("Add your bike first", "Keppo Moto records each ride on a bike. Add one on Home, then start from here.")
                        failed -> Message("The ride didn't start", "Open Home and start it from the slider.")
                        !allowed && !demo -> {
                            Message("Allow location", "Keppo Moto needs your location to record the ride.")
                            PrimaryButton("Allow", { permission.launch(Permissions.rideStartPermissions()) }, Modifier.fillMaxWidth())
                        }
                        else -> {
                            Text("Starting your ride", style = RtType.headline, color = RtColors.TextPrimary)
                            Box(
                                Modifier.size(160.dp).background(RtColors.Primary, CircleShape).semantics { liveRegion = LiveRegionMode.Polite },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$left", style = RtType.display, color = RtColors.OnPrimary)
                            }
                            Text(bike?.let { "${it.make} ${it.model}".trim() }.orEmpty(), style = RtType.body, color = RtColors.TextSecondary)
                        }
                    }
                    SecondaryButton(if (ready) "Cancel" else "Close", onClose, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Text(title, style = RtType.headline, color = RtColors.TextPrimary, textAlign = TextAlign.Center)
    Text(body, style = RtType.body, color = RtColors.TextSecondary, textAlign = TextAlign.Center)
}

private const val COUNTDOWN = 3
