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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.keepmoving.NavUpdatesService
import com.ridetrack.app.keepmoving.Planner
import com.ridetrack.app.moments.TwoMicKind
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keep moving's directions (25 Nov, prototype C2 with the HUD's three mic bars): Google's
 * navigation (turn banner, night map, silent) with Keppo's pieces in the slots Google lays its
 * screen out around. **Everything at a glance, nothing behind a tap, no voice, no vibration.**
 * - under Google's turn banner: the jam warning (amber), else a quiet note pill;
 * - the footer, one dock in three rows: status (camera, GPS, ride time) and the HUD's MIC, VOICE
 *   and ENGINE bars; riding (speed, lean, gear and RPM, Film, or REC with pause, flip and stop);
 *   the trip to the real destination with Ride screen and End. The clearer-road offer sits inside
 *   the dock; the dock's top edge turns amber for a warning and teal for an offer.
 * Google's trip bar, ETA card, Report button and speedometer are off; status and gesture bars padded.
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
            setTripProgressBarEnabled(false)
            setEtaCardEnabled(false)
            setReportIncidentButtonEnabled(false)
            setSpeedometerEnabled(false)
            setSpeedLimitIconEnabled(true)
            setTrafficPromptsEnabled(true)
            setCustomControl(panel(context) { Banner(c) }, CustomControlPosition.SECONDARY_HEADER)
            setCustomControl(panel(context) { Dock(c, onRideScreen) }, CustomControlPosition.FOOTER)
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
    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}

private fun panel(context: Context, content: @Composable () -> Unit) = ComposeView(context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent { RideDark { content() } }
}

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

private val Amber = Color(0xFFFBBF24)
private val Teal = Color(0xFF69C8CB)
private val Rec = Color(0xFFFF5A5A)
private val Engine = Color(0xFFF97316)
private val Chip = Color(0xFF1C1C1F)
private val Hairline = Color(0xFF1C1C1F)

/** Under Google's turn banner: the jam warning, else a quiet note for a few seconds. */
@Composable
private fun Banner(c: AppContainer) {
    val warning by c.keepMoving.warning.collectAsStateWithLifecycle()
    val guideNote by c.keepMoving.note.collectAsStateWithLifecycle()
    val w = warning
    if (w != null) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
                .background(Amber, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
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
    // Notes: the guide's ("Switched…"), or the HUD's ("Saved to moments · 0:42", a mic that dropped out).
    val d = c.hud.liveData
    val hudNote = d?.savedNote ?: d?.micFallback?.let { "$it dropped out · phone mic recording" }
    var shownAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(guideNote) {
        shownAt = System.currentTimeMillis()
        now = shownAt
        while (now - shownAt <= 8_000) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val n = guideNote?.takeIf { now - shownAt <= 8_000 } ?: hudNote ?: return
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

private val label = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
private val tnum = "tnum"

/** The dock: everything at a glance. */
@Composable
private fun Dock(c: AppContainer, onRideScreen: () -> Unit) {
    val guide = c.keepMoving
    val offer by guide.offer.collectAsStateWithLifecycle()
    val warning by guide.warning.collectAsStateWithLifecycle()
    val next by NavUpdatesService.next.collectAsStateWithLifecycle()
    val d = c.hud.liveData
    // A subtle cue on the dock itself: amber top edge for a warning, teal for an offer.
    val edge = when {
        offer != null -> Teal
        warning != null -> Amber
        else -> Color.Transparent
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0A0A0B), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 24.dp).background(edge, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            offer?.let { o -> OfferCard(c, o) }
            StatusRow(d)
            MicBars(d)
            RidingRow(c, d)
            TripRow(c, next, onRideScreen)
        }
    }
}

@Composable
private fun OfferCard(c: AppContainer, o: com.ridetrack.app.keepmoving.KeepMovingGuide.Offer) {
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
            Box(Modifier.size(40.dp).border(3.dp, Teal, CircleShape), contentAlignment = Alignment.Center) {
                Text("$left", style = RtType.bodyStrong, color = Teal)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Keep this road", Color(0xFF141416), RtColors.TextPrimary, Modifier.weight(1f), c.keepMoving::keepThisRoad)
            Pill("Switch now", Teal, Color(0xFF04292A), Modifier.weight(1f), c.keepMoving::switchNow)
        }
    }
}

/** Camera, GPS and the ride time. */
@Composable
private fun StatusRow(d: HudData?) {
    val filming = d?.video != null
    val gpsLost = d?.status == HudStatus.GPS_LOST
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val camText = when {
            filming -> "Cam · " + if (d!!.video!!.back) "back" else "front"
            d?.camera == com.ridetrack.app.hud.CameraIndicator.OFF || d == null -> "Cam off"
            else -> "Cam ready"
        }
        StatusChip(Icons.Outlined.Videocam, camText, if (filming) Rec else RtColors.TextPrimary, if (filming) Rec.copy(alpha = 0.14f) else Chip)
        StatusChip(null, if (gpsLost) "GPS lost" + (if (filming) " · filming" else "") else "GPS", if (gpsLost) Color(0xFF1C1300) else RtColors.Ok, if (gpsLost) Amber else Chip)
        Spacer(Modifier.weight(1f))
        Text("Ride " + (d?.elapsedMillis?.let(Format::clock) ?: "–"), style = RtType.caption.copy(fontFeatureSettings = tnum), color = RtColors.TextSecondary)
    }
}

@Composable
private fun StatusChip(icon: ImageVector?, text: String, fg: Color, bg: Color) {
    Row(Modifier.height(28.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = RtType.caption.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

/** The HUD's three mic bars: MIC (film-when-I-speak level and its mark), VOICE and ENGINE (two mics). */
@Composable
private fun MicBars(d: HudData?) {
    val hears = d?.speaking == true
    val micLevel = d?.micLevelDb
    val micFill = micLevel?.let { ((it - 0f) / 24f).coerceIn(0f, 1f) } ?: 0f
    val tick = d?.let { (it.voiceMarginDb / 24f).coerceIn(0f, 1f) }
    val two = d?.twoMics
    fun dbfs(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val mono = two?.kind == TwoMicKind.MONO
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MicBar(
            (if (hears) "MIC · HEARS YOU" else "MIC") + if (d?.phoneMic == true) " · PHONE" else "",
            if (d?.voiceOn == true) micFill else 0f,
            if (hears) Teal else RtColors.TextSecondary,
            tick.takeIf { d?.voiceOn == true },
            if (hears) Teal else RtColors.TextSecondary,
            Modifier.weight(1f),
        )
        MicBar("VOICE", two?.let { dbfs(it.voiceDb) } ?: 0f, Teal, null, RtColors.TextSecondary, Modifier.weight(1f))
        MicBar(if (mono) "ENGINE · MONO" else "ENGINE", two?.let { dbfs(it.engineDb) } ?: 0f, if (mono) RtColors.TextTertiary else Engine, null, if (mono) Amber else RtColors.TextSecondary, Modifier.weight(1f))
    }
}

@Composable
private fun MicBar(name: String, fill: Float, colour: Color, tick: Float?, labelColour: Color, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$name ${(fill * 100).roundToInt()} percent" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = label, color = labelColour, maxLines = 1)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).offset(y = 2.dp).background(RtColors.TextSecondary.copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
            Box(Modifier.width(maxWidth * fill).height(4.dp).offset(y = 2.dp).background(colour, RoundedCornerShape(2.dp)))
            tick?.let { t -> Box(Modifier.offset(x = maxWidth * t).width(2.dp).fillMaxHeight().background(RtColors.TextPrimary)) }
        }
    }
}

/** Speed, lean, gear and RPM; Film, or REC with pause, flip and stop. */
@Composable
private fun RidingRow(c: AppContainer, d: HudData?) {
    val ctrl = c.hud.controls
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.speedValue(d?.speedMps),
                style = TextStyle(fontSize = 56.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light, fontFeatureSettings = tnum),
                color = RtColors.TextPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text("km/h", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        val lean = d?.leanDeg
        val leanColour = when {
            lean == null -> RtColors.TextTertiary
            lean < 0 -> Color(0xFFA5A1FF)
            else -> Color(0xFFFB7185)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.lean(lean), style = RtType.bodyStrong.copy(fontFeatureSettings = tnum), color = leanColour)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.width(52.dp).height(4.dp).background(Color(0xFF2A2A2E), RoundedCornerShape(2.dp))) {
                val f = ((abs(lean ?: 0.0)).coerceAtMost(60.0) / 60.0).toFloat()
                Box(Modifier.offset(x = if ((lean ?: 0.0) >= 0) maxWidth / 2 else maxWidth / 2 - maxWidth / 2 * f).width(maxWidth / 2 * f).height(4.dp).background(leanColour, RoundedCornerShape(2.dp)))
            }
        }
        if (d?.gear != null || d?.rpm != null) {
            Column {
                Text(d.gear?.let(Format::gear) ?: "–", style = RtType.bodyStrong.copy(fontFeatureSettings = tnum), color = RtColors.TextPrimary)
                Text(d.rpm?.let { Format.rpm(it) + " rpm" } ?: "", style = RtType.caption.copy(fontFeatureSettings = tnum), color = RtColors.TextSecondary)
            }
        }
        Spacer(Modifier.weight(1f))
        val video = d?.video
        if (video == null) {
            Row(
                Modifier.heightIn(min = 44.dp).background(Chip, RoundedCornerShape(50)).border(1.dp, RtColors.Outline, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Film") { ctrl.recordVideo() }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Rec, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Film", style = RtType.caption.copy(fontWeight = FontWeight.SemiBold), color = RtColors.TextPrimary)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.heightIn(min = 44.dp).background(Rec.copy(alpha = 0.14f), RoundedCornerShape(50)).border(1.dp, Rec, RoundedCornerShape(50)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(if (video.paused) RtColors.TextTertiary else Rec, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(Format.clock(video.elapsedMillis), style = RtType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum), color = Rec)
                }
                RoundIcon(if (video.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (video.paused) "Resume filming" else "Pause filming", RtColors.TextPrimary, 44) {
                    if (video.paused) ctrl.resumeVideo() else ctrl.pauseVideo()
                }
                RoundIcon(Icons.Outlined.Cameraswitch, "Flip camera", RtColors.TextPrimary, 44) { ctrl.flipCamera() }
                RoundIcon(Icons.Rounded.Stop, "Stop filming", Rec, 44) { ctrl.stopVideo() }
            }
        }
    }
}

/** The trip to the real destination (not the next detour point), the mode, Ride screen and End. */
@Composable
private fun TripRow(c: AppContainer, next: NavUpdatesService.NextTurn?, onRideScreen: () -> Unit) {
    val guide = c.keepMoving
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(next?.toGoSeconds?.let { "${(it / 60.0).roundToInt()} min" } ?: "–", style = RtType.headline.copy(fontFeatureSettings = tnum), color = RtColors.Ok)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(
                            next?.toGoMeters?.let { Planner.km(it) },
                            next?.toGoSeconds?.let { LocalTime.now().plusSeconds(it.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")) },
                        ).joinToString(" · "),
                        style = RtType.caption.copy(fontFeatureSettings = tnum),
                        color = RtColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text((guide.mode.label + (guide.destination?.let { " · ${it.name}" } ?: "")).uppercase(), style = RtType.label, color = Teal, maxLines = 1)
            }
            RoundIcon(Icons.Outlined.Speed, "Ride screen", RtColors.TextPrimary, 48, onRideScreen)
            RoundIcon(Icons.Rounded.Stop, "End directions", RtColors.Error, 48) { guide.stop() }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).background(Chip, CircleShape).border(1.dp, RtColors.Outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.45f).dp))
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
