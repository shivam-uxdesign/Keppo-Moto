package com.ridetrack.app.ui.keepmoving

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.Planner
import com.ridetrack.app.keepmoving.KeepMode
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.MapType
import com.ridetrack.app.R
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.ui.components.MapStyleToggle
import kotlinx.coroutines.flow.map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.ridetrack.app.keepmoving.Destination
import com.ridetrack.app.keepmoving.LatLon
import com.ridetrack.app.keepmoving.Ranked
import com.ridetrack.app.keepmoving.RouteScore
import com.ridetrack.app.keepmoving.Speed
import com.ridetrack.app.keepmoving.TrafficRoutes
import com.ridetrack.app.ride.QuickStart
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.rides.MyLocation
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Traffic as the app's own colours (its dark palette's ok / warning / error), on the dark Keppo
 * style of Google's map ([R.raw.google_map_dark], the MapTiler dark map's greys), so this screen
 * looks like the rest of the app while still reading green / yellow / red like Google Maps.
 */
private val ClearColour = Color(0xFF4ADE80)
private val SlowColour = Color(0xFFFBBF24)
private val JamColour = Color(0xFFF43F5E)
private val NoDataColour = Color(0xFF6B6B70)
private val OtherRouteColour = Color(0xCC3F3F44)
/** Keppo Teal, for the destination pin and your position. */
private val Teal = Color(0xFF69C8CB)

/** A round marker: [fill] with a [ring], [sizeDp] across. */
private fun dot(context: android.content.Context, sizeDp: Float, fill: Color, ring: Color, ringDp: Float, centre: Color? = null): com.google.android.gms.maps.model.BitmapDescriptor {
    val d = context.resources.displayMetrics.density
    val px = (sizeDp * d).roundToInt()
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = px / 2f
    paint.color = ring.toArgb()
    cv.drawCircle(r, r, r, paint)
    paint.color = fill.toArgb()
    cv.drawCircle(r, r, r - ringDp * d, paint)
    centre?.let { paint.color = it.toArgb(); cv.drawCircle(r, r, r * 0.32f, paint) }
    return com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
}

private fun Speed.colour() = when (this) {
    Speed.NORMAL -> ClearColour
    Speed.SLOW -> SlowColour
    Speed.JAM -> JamColour
    Speed.UNKNOWN -> NoDataColour
}

private fun LatLon.g() = LatLng(lat, lon)

/**
 * Keep moving when starting a ride (test builds): Where to? first ([WhereTo]), then the plan:
 * the mode (Normal / Less traffic / No foot down, remembered), Keppo's clearest road with any
 * detours ([Planner]) on Google's map (its terms want its routes on its own map), the trade-off
 * against Google's fastest, and Start with directions. Normal makes no route request: Google's
 * navigation plans it when you start.
 */
@Composable
fun KeepMovingScreen(onBack: () -> Unit, onStarted: () -> Unit, onGuiding: () -> Unit = onStarted, autoStartSeconds: Int? = null) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = c.trafficLog
    val mode by c.keepMovingPrefs.mode.collectAsStateWithLifecycle()

    var dest by remember { mutableStateOf<Destination?>(null) }
    var here by remember { mutableStateOf<LatLon?>(null) }
    var allowed by remember { mutableStateOf(MyLocation.allowed(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<Planner.Plan?>(null) }
    var progress by remember { mutableStateOf<Planner.Progress?>(null) }
    var planning by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Planner.Option?>(null) }
    var starting by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var ownRides by remember { mutableStateOf<List<LatLon>>(emptyList()) }
    val mapStyle by remember { c.settings.settings.map { it.mapStyle } }.collectAsStateWithLifecycle(initialValue = MapStyle.DARK)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = MyLocation.allowed(context)
    }
    LaunchedEffect(allowed) {
        if (allowed) MyLocation.get(context) { here = LatLon(it.latitude, it.longitude) } else permission.launch(Permissions.rideStartPermissions())
    }
    LaunchedEffect(Unit) { ownRides = Destinations.ridePoints(c.rides) }

    /** Starts the ride: plain (no destination), or with directions along [option] to [d]. */
    fun ride(d: Destination?, option: Planner.Option?) {
        if (starting) return
        starting = true
        scope.launch {
            val bike = QuickStart.bike(c.bikes.observeBikes().first(), c.settings.settings.first().selectedBikeId)
            if (d != null) {
                val forSaving = Destinations.forSaving(d, c.places)
                log.setDestination(forSaving)
                log.plan(option?.ranked?.route)
                c.savedPlaces.used(forSaving, forSaving.name)
            } else {
                log.setDestination(null)
            }
            val ok = bike != null && QuickStart.start(c, bike)
            if (!ok) {
                starting = false
                problem = if (bike == null) "Add a bike first (Home › Add bike)" else "The ride didn't start: try again"
                return@launch
            }
            if (d == null) { onStarted(); return@launch }
            // Then Google's directions; the ride goes on either way.
            val activity = context.findActivity()
            // Ways round: the plan's other detours (the guide adds roads where you move well at the hour).
            val ways = plan?.options.orEmpty().filter { it !== option }.flatMap { it.via }
            val why = if (activity != null) c.keepMoving.start(activity, d, option, mode, ways) else "Directions couldn't start here"
            if (why == null) {
                onGuiding()
            } else {
                android.widget.Toast.makeText(context, "$why. Riding without directions.", android.widget.Toast.LENGTH_LONG).show()
                onStarted()
            }
        }
    }

    // Step 1: where to.
    val d0 = dest
    if (d0 == null) {
        WhereTo(here, autoStartSeconds, onPick = { dest = it }, onJustRide = { ride(null, null) }, onBack = onBack)
        return
    }

    // Step 2: the plan, again whenever the mode changes or Refresh is tapped.
    val haveHere = here != null
    LaunchedEffect(d0, mode, refresh, haveHere) {
        val from = here ?: return@LaunchedEffect
        plan = null
        picked = null
        problem = null
        progress = null
        if (!mode.scored) return@LaunchedEffect
        planning = true
        val p = c.planner.plan(from, d0.at, mode, ownRides) { progress = it }
        planning = false
        plan = p
        problem = p.problem
        picked = p.options.firstOrNull { !it.ranked.tooLong }
    }

    val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(d0.at.lat, d0.at.lon), 12f) }
    LaunchedEffect(plan, mapLoaded, haveHere) {
        if (!mapLoaded) return@LaunchedEffect
        val points = plan?.options.orEmpty().flatMap { TrafficRoutes.decodePolyline(it.ranked.route.polyline) } + listOfNotNull(here, d0.at)
        if (points.size >= 2) {
            val b = LatLngBounds.builder().apply { points.forEach { include(it.g()) } }.build()
            runCatching { cam.animate(CameraUpdateFactory.newLatLngBounds(b, 120)) }
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding()) {
        ScreenHeader(
            "Keep moving",
            subtitle = "To ${d0.name}",
            onBack = { dest = null },
            modifier = Modifier.padding(horizontal = RtDimens.xs),
            actions = {
                IconButton(onClick = { refresh++ }, enabled = mode.scored && here != null && !planning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Check the traffic again", tint = RtColors.TextPrimary)
                }
            },
        )
        // How do you want to ride?
        Row(Modifier.fillMaxWidth().padding(horizontal = RtDimens.screenPadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeepMode.entries.forEach { m -> ModeChip(m, m == mode, Modifier.weight(1f)) { c.keepMovingPrefs.setMode(m) } }
        }
        Text(modeHelp(mode), style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(horizontal = RtDimens.screenPadding, vertical = 6.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val darkStyle = remember { runCatching { MapStyleOptions.loadRawResourceStyle(context, R.raw.google_map_dark) }.getOrNull() }
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cam,
                // The Dark / Satellite choice is the same setting as the ride maps'.
                properties = MapProperties(
                    mapType = if (mapStyle == MapStyle.SATELLITE) MapType.HYBRID else MapType.NORMAL,
                    mapStyleOptions = if (mapStyle == MapStyle.SATELLITE) null else darkStyle,
                ),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, compassEnabled = false),
                onMapLoaded = { mapLoaded = true },
            ) {
                val options = plan?.options.orEmpty()
                // The other routes first, grey, so the picked one draws on top.
                options.filter { it !== picked }.forEach { o ->
                    Polyline(
                        points = TrafficRoutes.decodePolyline(o.ranked.route.polyline).map { it.g() },
                        color = OtherRouteColour,
                        width = 14f,
                        clickable = !o.ranked.tooLong,
                        onClick = { picked = o },
                    )
                }
                picked?.let { o ->
                    RouteScore.stretches(o.ranked.route).forEach { s ->
                        Polyline(points = s.points.map { it.g() }, color = s.speed.colour(), width = 18f, zIndex = 2f)
                    }
                }
                val pin = remember { dot(context, 26f, Teal, Color.White, 3f, centre = Color(0xFF0A0A0B)) }
                val me = remember { dot(context, 18f, Color.White, Teal, 4f) }
                val wayPin = remember { dot(context, 14f, Color(0xFF0A0A0B), Teal, 3f) }
                val destState = remember(d0.at) { MarkerState(position = d0.at.g()) }
                Marker(state = destState, title = d0.name, icon = pin, anchor = Offset(0.5f, 0.5f), zIndex = 4f)
                here?.let { h ->
                    val meState = remember(h) { MarkerState(position = h.g()) }
                    Marker(state = meState, title = "You", icon = me, anchor = Offset(0.5f, 0.5f), zIndex = 3f)
                }
                picked?.via?.forEach { v ->
                    key(v) {
                        val wayState = remember(v) { MarkerState(position = v.g()) }
                        Marker(state = wayState, title = "Way round", icon = wayPin, anchor = Offset(0.5f, 0.5f), zIndex = 3f)
                    }
                }
            }
            MapStyleToggle(
                selected = mapStyle,
                onSelect = { st -> scope.launch { c.settings.setMapStyle(st) } },
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            if (planning) {
                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
        }

        // The search, the pick and the trade-off, then Start.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RtDimens.screenPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val p = plan
            when {
                !allowed -> Message(
                    "Keep moving needs your location to find routes from where you are.",
                    action = "Allow location" to { permission.launch(Permissions.rideStartPermissions()) },
                )
                here == null -> Message("Finding where you are…")
                !mode.scored -> Message("Google's usual fastest route. It's planned when you start: no extra searching, nothing extra to pay.")
                planning -> progress?.let { pr -> SearchProgress(pr) } ?: Message("Checking Google's routes and their traffic…")
                problem != null -> Message(problem!!, action = "Try again" to { refresh++ })
                p != null && picked != null -> {
                    if (p.carRoutes) Text("Google has no two-wheeler routes here: these are car routes.", style = RtType.caption, color = RtColors.Warning)
                    PickCard(picked!!, mode)
                    val g = p.google
                    if (g != null && picked!!.ranked.route !== g.route) TradeOff(g, picked!!.ranked)
                    progress?.steps?.takeIf { it.isNotEmpty() }?.let { steps ->
                        Text("Tried ${steps.size} ways round" + if (p.signalsCounted) " · signals from © OpenStreetMap contributors" else "", style = RtType.caption, color = RtColors.TextTertiary)
                    } ?: run {
                        if (p.signalsCounted) Text("Signals from © OpenStreetMap contributors", style = RtType.caption, color = RtColors.TextTertiary)
                    }
                    Text(costNote(p, picked!!), style = RtType.caption, color = RtColors.TextTertiary)
                }
            }
        }
        PrimaryButton(
            if (starting) "Starting…" else "Start with directions",
            enabled = !starting && here != null && (!mode.scored || picked != null || problem != null),
            large = true,
            onClick = { ride(d0, picked.takeIf { mode.scored }) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = RtDimens.screenPadding).padding(bottom = 12.dp).navigationBarsPadding(),
        )
    }
}

private fun modeHelp(m: KeepMode) = when (m) {
    KeepMode.NORMAL -> "Google's usual fastest route."
    KeepMode.LESS_TRAFFIC -> "Fewer jams, up to 2× longer: the clearest of Google's routes, with detours if they all have jams."
    KeepMode.NO_FOOT_DOWN -> "Avoids every stop it can (jams, slow traffic, signals) and tries detours, up to 3× longer."
}

private fun costNote(p: Planner.Plan, o: Planner.Option): String {
    val stops = o.via.size + 1
    val rupees = p.requests * 1.3 + stops * 2.2
    return "${p.requests} route request${if (p.requests == 1) "" else "s"} + $stops destination${if (stops == 1) "" else "s"} · ≈ ₹${"%.0f".format(rupees)} past the free amounts"
}

@Composable
private fun ModeChip(m: KeepMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .heightIn(min = 64.dp)
            .background(if (selected) Color(0xFF1E3E40) else RtColors.SurfaceRaised, shape)
            .border(2.dp, if (selected) RtColors.Primary else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(m.label, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
        Text(
            when (m) { KeepMode.NORMAL -> "Google's fastest"; KeepMode.LESS_TRAFFIC -> "Fewer jams"; KeepMode.NO_FOOT_DOWN -> "Keep rolling" },
            style = RtType.caption,
            color = RtColors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchProgress(p: Planner.Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Looking for a clearer road", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text("Google's routes all have jams: trying ways round · ${p.tried} of ${p.budget}", style = RtType.caption, color = RtColors.TextSecondary)
        p.steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (s.ok) ClearColour else NoDataColour, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("${s.title} · ${s.detail}", style = RtType.caption, color = RtColors.TextPrimary, maxLines = 2)
            }
        }
    }
}

@Composable
private fun PickCard(o: Planner.Option, mode: KeepMode) {
    val r = o.ranked
    RtCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(r.route.durationSec / 60.0).roundToInt()} min · ${Planner.km(r.route.distanceM)}", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(if (r.clear) "NO JAMS" else "CLEAREST", style = RtType.label, color = RtColors.Primary)
        }
        Text(
            listOfNotNull(
                if (r.colours.jamM == 0) "No jams" else "${Planner.km(r.colours.jamM)} jammed",
                Planner.km(r.colours.slowM).takeIf { r.colours.slowM > 0 }?.let { "$it slow" },
                "${r.signals} signal${if (r.signals == 1) "" else "s"}".takeIf { mode.signalCost > 0 },
            ).joinToString(" · "),
            style = RtType.body,
            color = RtColors.TextPrimary,
        )
        val via = listOfNotNull(r.route.description?.let { "via $it" }, "${o.via.size} way${if (o.via.size == 1) "" else "s"} round".takeIf { o.via.isNotEmpty() })
        if (via.isNotEmpty()) Text(via.joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
    }
}

@Composable
private fun TradeOff(google: Ranked, pick: Ranked) {
    val extra = ((pick.route.durationSec - google.route.durationSec) / 60.0).roundToInt()
    RtCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Google's fastest", style = RtType.caption, color = RtColors.TextSecondary)
                Text(
                    "${(google.route.durationSec / 60.0).roundToInt()} min · ${Planner.km(google.route.distanceM)} · " +
                        if (google.colours.jamM == 0) "no jams" else "${Planner.km(google.colours.jamM)} jammed",
                    style = RtType.body,
                    color = RtColors.TextPrimary,
                )
            }
            if (extra > 0) Text("+$extra min", style = RtType.caption, color = RtColors.Warning)
        }
    }
}

@Composable
private fun Message(text: String, action: Pair<String, () -> Unit>? = null) {
    RtCard {
        Text(text, style = RtType.body, color = RtColors.TextPrimary)
        action?.let { (label, go) ->
            Spacer(Modifier.height(6.dp))
            Text(label, style = RtType.bodyStrong, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = go).padding(vertical = 6.dp))
        }
    }
}


private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
