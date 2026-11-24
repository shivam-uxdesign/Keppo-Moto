package com.ridetrack.app.ui.keepmoving

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.navigation.CustomControlPosition
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import com.ridetrack.app.AppContainer
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.hud.HudOverlayContent
import com.ridetrack.app.ui.theme.LocalAccent
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay

/**
 * Keep moving's directions (test builds): Google's navigation (arrows, voice, its night map, so it's
 * dark like the riding screens), with Keppo's own pieces in the slots Google lays its screen out
 * around, so they never cover its turn arrow or ETA:
 * - bottom left, the **fixed HUD**: the same card as the floating pop-up (speed, lean, gear and RPM
 *   from OBD, the camera and its video buttons); Google's own speedometer is off, its speed-limit
 *   sign stays. The floating pop-up hides while the app is on screen, as always.
 * - the footer: jam warnings, the 5-second clearer-road offer, Ride screen and End directions.
 * - under the turn header: short notes ("Switched to a clearer road").
 */
@Composable
fun KeepMovingNavScreen(onRideScreen: () -> Unit, onEnded: () -> Unit) {
    val c = appContainer()
    val guide = c.keepMoving
    val context = LocalContext.current
    val active by guide.active.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        NavigationView(context).apply {
            onCreate(null)
            setForceNightMode(ForceNightMode.FORCE_NIGHT)
            setTripProgressBarEnabled(true)
            setSpeedometerEnabled(false)
            setSpeedLimitIconEnabled(true)
            setTrafficPromptsEnabled(true)
            setCustomControl(panel(context) { FixedHud(c) }, CustomControlPosition.BOTTOM_START_BELOW)
            setCustomControl(panel(context) { Footer(c, onRideScreen) }, CustomControlPosition.FOOTER)
            setCustomControl(panel(context) { Note(c) }, CustomControlPosition.SECONDARY_HEADER)
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
    }
}

/** A Compose panel for one of the navigation's custom-control slots. */
private fun panel(context: Context, content: @Composable () -> Unit) = ComposeView(context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent { RideDark { content() } }
}

/** The floating HUD's card, fixed in place: same data, look and video buttons. */
@Composable
private fun FixedHud(c: AppContainer) {
    val hud = c.hud
    val d = hud.liveData ?: return
    CompositionLocalProvider(LocalAccent provides hud.liveAccent) {
        Box(Modifier.padding(8.dp)) {
            HudOverlayContent(d, hud.liveSettings, collapsed = false, controlsOpen = false, actions = hud.controls, viewfinder = hud.liveViewfinder)
        }
    }
}

/** Short notes under Google's turn header, for a few seconds. */
@Composable
private fun Note(c: AppContainer) {
    val note by c.keepMoving.note.collectAsStateWithLifecycle()
    var shownAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(note) {
        shownAt = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            if (now - shownAt > 8_000) break
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val n = note ?: return
    if (now - shownAt > 8_000) return
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .background(RtColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(RtColors.Ok, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(n, style = RtType.body, color = RtColors.TextPrimary)
    }
}

/** Warnings, the clearer-road offer, and Keppo's two buttons. */
@Composable
private fun Footer(c: AppContainer, onRideScreen: () -> Unit) {
    val guide = c.keepMoving
    val offer by guide.offer.collectAsStateWithLifecycle()
    val warning by guide.warning.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(RtColors.Background).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val o = offer
        val w = warning
        if (o != null) {
            var left by remember(o) { mutableLongStateOf(((o.atMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
            LaunchedEffect(o) {
                while (left > 0) {
                    delay(250)
                    left = ((o.atMs - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                }
            }
            Column(Modifier.fillMaxWidth().background(RtColors.Surface, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A clearer road", style = RtType.headline, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                    Box(Modifier.size(44.dp).background(Color(0xFF1E3E40), CircleShape), contentAlignment = Alignment.Center) {
                        Text("$left", style = RtType.headline, color = RtColors.Primary)
                    }
                }
                Text(
                    listOfNotNull(
                        "${com.ridetrack.app.keepmoving.Planner.km(o.savedJamM)} less jam".takeIf { o.savedJamM > 0 },
                        "${com.ridetrack.app.keepmoving.Planner.km(o.savedSlowM)} less slow".takeIf { o.savedSlowM > 0 },
                    ).joinToString(" · ").ifEmpty { "Clearer ahead" } + " · switching on its own",
                    style = RtType.body,
                    color = RtColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Keep this road", onClick = guide::keepThisRoad, modifier = Modifier.weight(1f))
                    PrimaryButton("Switch now", onClick = guide::switchNow, large = true, modifier = Modifier.weight(1f))
                }
            }
        } else if (w != null) {
            Column(Modifier.fillMaxWidth().background(RtColors.Surface, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = RtColors.Warning, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(w.title, style = RtType.headline, color = RtColors.TextPrimary)
                }
                Text(w.detail, style = RtType.body, color = RtColors.TextSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Ride screen", onClick = onRideScreen, modifier = Modifier.weight(1f))
            SecondaryButton("End directions", onClick = { guide.stop() }, modifier = Modifier.weight(1f), contentColor = RtColors.Error)
        }
        Spacer(Modifier.height(2.dp))
    }
}
