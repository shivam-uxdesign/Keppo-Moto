package com.ridetrack.app.ui.keepmoving

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.navigation.CustomControlPosition
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import com.ridetrack.app.AppContainer
import com.ridetrack.app.keepmoving.NavUpdatesService
import com.ridetrack.app.keepmoving.Planner
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.hud.HudOverlayContent
import com.ridetrack.app.ui.theme.LocalAccent
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keep moving's directions (25 Nov redesign, prototype option C with B's warning banner):
 * Google's navigation (turn banner, night map, voice) with Keppo's pieces in the slots Google lays
 * its screen out around:
 * - under Google's turn banner: jam warnings (amber), or a short note;
 * - the footer: **one dock** with speed, lean, gear and RPM, the trip to the real destination,
 *   REC, Ride screen and End; the clearer-road offer appears inside it;
 * - tap the speed: the full HUD card (all its camera and mic controls) bottom left, tap again to hide.
 * Google's trip bar, ETA card and Report button are off; the screen leaves room for the status and
 * gesture bars.
 */
@Composable
fun KeepMovingNavScreen(onRideScreen: () -> Unit, onEnded: () -> Unit) {
    val c = appContainer()
    val guide = c.keepMoving
    val context = LocalContext.current
    val active by guide.active.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val fullHud = remember { MutableStateFlow(false) }
    val view = remember {
        NavigationView(context).apply {
            onCreate(null)
            setForceNightMode(ForceNightMode.FORCE_NIGHT)
            setTripProgressBarEnabled(false)
            setEtaCardEnabled(false)
            setReportIncidentButtonEnabled(false)
            setSpeedometerEnabled(false)
            setSpeedLimitIconEnabled(true)
            setTrafficPromptsEnabled(true)
            setCustomControl(panel(context) { Banner(c) }, CustomControlPosition.SECONDARY_HEADER)
            setCustomControl(panel(context) { FullHud(c, fullHud) }, CustomControlPosition.BOTTOM_START_BELOW)
            setCustomControl(panel(context) { Dock(c, fullHud, onRideScreen) }, CustomControlPosition.FOOTER)
            // The real destination (Google's own pins are hidden: detour points showed as the destination).
            getMapAsync { map ->
                guide.destination?.let { d ->
                    runCatching {
                        map.addMarker(MarkerOptions().position(LatLng(d.at.lat, d.at.lon)).title(d.name).anchor(0.5f, 0.5f).icon(BitmapDescriptorFactory.fromBitmap(destinationDot(context))))
                    }
                }
            }
        }
    }
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
    // Room for the status bar (Google's banner sat under the clock) and the gesture bar.
    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}

/** A Compose panel for one of the navigation's custom-control slots. */
private fun panel(context: Context, content: @Composable () -> Unit) = ComposeView(context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent { RideDark { content() } }
}

/** Keppo Teal with a dark centre: the destination. */
private fun destinationDot(context: Context): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val px = (26 * d).roundToInt()
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = px / 2f
    p.color = Color.White.toArgb(); cv.drawCircle(r, r, r, p)
    p.color = Color(0xFF69C8CB).toArgb(); cv.drawCircle(r, r, r - 3 * d, p)
    p.color = Color(0xFF04292A).toArgb(); cv.drawCircle(r, r, r * 0.32f, p)
    return bmp
}

/** Under Google's turn banner: a jam warning (amber), else a short note for a few seconds. */
@Composable
private fun Banner(c: AppContainer) {
    val warning by c.keepMoving.warning.collectAsStateWithLifecycle()
    val note by c.keepMoving.note.collectAsStateWithLifecycle()
    val w = warning
    if (w != null) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
                .background(Color(0xFFFBBF24), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFF1C1300), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(w.title, style = RtType.headline.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF1C1300))
                Text(w.detail, style = RtType.body, color = Color(0xFF1C1300))
            }
        }
        return
    }
    var shownAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(note) {
        shownAt = System.currentTimeMillis()
        now = shownAt
        while (now - shownAt <= 8_000) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val n = note ?: return
    if (now - shownAt > 8_000) return
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
            .background(RtColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(RtColors.Ok, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(n, style = RtType.body, color = RtColors.TextPrimary)
    }
}

/** The full HUD card (every camera and mic control), shown while the rider has it open. */
@Composable
private fun FullHud(c: AppContainer, open: MutableStateFlow<Boolean>) {
    val show by open.collectAsStateWithLifecycle()
    if (!show) return
    val hud = c.hud
    val d = hud.liveData ?: return
    CompositionLocalProvider(LocalAccent provides hud.liveAccent) {
        Box(Modifier.padding(8.dp)) {
            HudOverlayContent(d, hud.liveSettings, collapsed = false, controlsOpen = false, actions = hud.controls, viewfinder = hud.liveViewfinder)
        }
    }
}

private val bigNumber = TextStyle(fontSize = 52.sp, lineHeight = 50.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.04).em(), fontFeatureSettings = "tnum")
private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** One dock: speed and lean, the trip, REC and the controls; the clearer-road offer inside. */
@Composable
private fun Dock(c: AppContainer, fullHud: MutableStateFlow<Boolean>, onRideScreen: () -> Unit) {
    val guide = c.keepMoving
    val offer by guide.offer.collectAsStateWithLifecycle()
    val next by NavUpdatesService.next.collectAsStateWithLifecycle()
    val hudOpen by fullHud.collectAsStateWithLifecycle()
    val hud = c.hud
    val d = hud.liveData
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0A0A0B), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        offer?.let { o ->
            var left by remember(o) { mutableLongStateOf(((o.atMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
            LaunchedEffect(o) {
                while (left > 0) {
                    delay(250)
                    left = ((o.atMs - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                }
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFF1E3E40), RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "A clearer road" + listOfNotNull(
                            "${Planner.km(o.savedJamM)} less jam".takeIf { o.savedJamM > 0 },
                            "${Planner.km(o.savedSlowM)} less slow".takeIf { o.savedSlowM > 0 },
                        ).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" },
                        style = RtType.bodyStrong,
                        color = RtColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(40.dp).border(3.dp, RtColors.Primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$left", style = RtType.bodyStrong, color = RtColors.Primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Keep this road", RtColors.Surface, RtColors.TextPrimary, Modifier.weight(1f), guide::keepThisRoad)
                    Pill("Switch now", RtColors.Primary, RtColors.OnPrimary, Modifier.weight(1f), guide::switchNow)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Speed and lean (tap: the full HUD card).
            Column(
                Modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = if (hudOpen) "Hide the full HUD" else "Show the full HUD") { fullHud.value = !hudOpen },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(d?.speedMps?.let { "${(it * 3.6).roundToInt()}" } ?: "–", style = bigNumber, color = RtColors.TextPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("km/h", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    listOfNotNull(
                        d?.leanDeg?.let { "${abs(it).roundToInt()}° ${if (it < 0) "L" else "R"}" },
                        d?.gear?.let { com.ridetrack.app.ui.format.Format.gear(it) },
                        d?.rpm?.let { String.format(java.util.Locale.US, "%.1fk", it / 1000) },
                    ).joinToString(" · ").ifEmpty { "Lean –" },
                    style = RtType.caption.copy(fontFeatureSettings = "tnum"),
                    color = RtColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(1.dp).height(56.dp).background(Color(0xFF1C1C1F)))
            Spacer(Modifier.width(14.dp))
            // The trip to the real destination (not the next detour point).
            Column(Modifier.weight(1f)) {
                val n = next
                Text(
                    n?.toGoSeconds?.let { "${(it / 60.0).roundToInt()} min" } ?: "–",
                    style = RtType.headline.copy(fontFeatureSettings = "tnum"),
                    color = RtColors.Ok,
                )
                Text(
                    listOfNotNull(
                        n?.toGoMeters?.let { Planner.km(it) },
                        n?.toGoSeconds?.let { LocalTime.now().plusSeconds(it.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")) },
                    ).joinToString(" · "),
                    style = RtType.caption.copy(fontFeatureSettings = "tnum"),
                    color = RtColors.TextSecondary,
                )
                Text(
                    (guide.mode.label + (guide.destination?.let { " · ${it.name}" } ?: "")).uppercase(),
                    style = RtType.label,
                    color = RtColors.Primary,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rec(c)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIcon(Icons.Outlined.Speed, "Ride screen", RtColors.TextPrimary, onRideScreen)
                    RoundIcon(Icons.Rounded.Stop, "End directions", RtColors.Error) { guide.stop() }
                }
            }
        }
    }
}

/** Film / REC: one small control (stop while filming). */
@Composable
private fun Rec(c: AppContainer) {
    val d = c.hud.liveData
    val filming = d?.video != null
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 40.dp)
            .background(if (filming) Color(0x24FF5A5A) else RtColors.SurfaceRaised, shape)
            .border(1.dp, if (filming) Color(0xFFFF5A5A) else RtColors.Outline, shape)
            .clickable(role = Role.Button) { if (filming) c.hud.controls.stopVideo() else c.hud.controls.recordVideo() }
            .semantics { contentDescription = if (filming) "Stop filming" else "Film" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (filming) Color(0xFFFF5A5A) else RtColors.TextTertiary, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            if (filming) com.ridetrack.app.ui.format.Format.clock(d!!.video!!.elapsedMillis) else "Film",
            style = RtType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = if (filming) Color(0xFFFF5A5A) else RtColors.TextPrimary,
        )
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(RtColors.SurfaceRaised, CircleShape).border(1.dp, RtColors.Outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 56.dp).background(bg, RoundedCornerShape(50)).border(1.dp, RtColors.Outline, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RtType.button, color = fg)
    }
}
