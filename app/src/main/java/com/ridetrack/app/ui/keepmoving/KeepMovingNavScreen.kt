package com.ridetrack.app.ui.keepmoving

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay

/**
 * Keep moving's directions (test builds): Google's navigation (arrows, voice, its night map so it's
 * dark like the riding screens), and over it the offer of a clearer road with "Keep this road",
 * plus the way back to the ride screen and to end directions.
 */
@Composable
fun KeepMovingNavScreen(onRideScreen: () -> Unit, onEnded: () -> Unit) {
    val c = appContainer()
    val guide = c.keepMoving
    val context = LocalContext.current
    val active by guide.active.collectAsStateWithLifecycle()
    val offer by guide.offer.collectAsStateWithLifecycle()
    val note by guide.note.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        NavigationView(context).apply {
            onCreate(null)
            setForceNightMode(ForceNightMode.FORCE_NIGHT)
            setTripProgressBarEnabled(true)
            setSpeedometerEnabled(true)
            setTrafficPromptsEnabled(true)
        }
    }
    // The navigation view follows the screen's lifecycle.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.onDestroy()
        }
    }
    LaunchedEffect(active) { if (!active) onEnded() }

    Box(Modifier.fillMaxSize().background(RtColors.Background)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding, vertical = 12.dp)) {
            offer?.let { o ->
                var left by remember(o) { mutableLongStateOf(((o.atMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
                LaunchedEffect(o) {
                    while (left > 0) {
                        delay(1_000)
                        left = ((o.atMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(RtColors.Surface, RoundedCornerShape(RtDimens.cardRadius)).padding(14.dp),
                ) {
                    Text("Traffic ahead · a clearer road", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(
                        listOfNotNull(
                            "${o.savedJamM} m less jam".takeIf { o.savedJamM > 0 },
                            "${o.savedSlowM} m less slow".takeIf { o.savedSlowM > 0 },
                            "${(o.route.route.durationSec / 60.0).toInt()} min".takeIf { o.route.route.durationSec > 0 },
                        ).joinToString(" · ") + " · switching in ${left}s",
                        style = RtType.caption,
                        color = RtColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Keep this road", onClick = guide::keepThisRoad, modifier = Modifier.weight(1f))
                        PrimaryButton("Switch now", onClick = guide::switchNow, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Ride screen", onClick = onRideScreen, modifier = Modifier.weight(1f))
                SecondaryButton("End directions", onClick = { guide.stop() }, modifier = Modifier.weight(1f), contentColor = RtColors.Error)
            }
        }
        note?.let { n ->
            Text(
                n,
                style = RtType.caption,
                color = RtColors.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 120.dp)
                    .background(RtColors.Surface.copy(alpha = 0.9f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
