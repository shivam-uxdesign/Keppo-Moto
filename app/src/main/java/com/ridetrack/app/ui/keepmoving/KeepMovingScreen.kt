package com.ridetrack.app.ui.keepmoving

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ridetrack.app.ui.profile.DestinationPicker
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
 * Keep moving, step 1 (test builds): where you're going, Google's routes there with live traffic on
 * Google's map (its terms want its routes on its own map), the clearest one picked by
 * [RouteScore], and Start. The ride's traffic log (step 0) carries on during the ride, with the
 * picked route in its first line.
 */
@Composable
fun KeepMovingScreen(onBack: () -> Unit, onStarted: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = c.trafficLog
    val saved by log.destination.collectAsStateWithLifecycle()

    var dest by remember { mutableStateOf(saved) }
    var here by remember { mutableStateOf<LatLon?>(null) }
    var allowed by remember { mutableStateOf(MyLocation.allowed(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var carRoutes by remember { mutableStateOf(false) }
    var ranked by remember { mutableStateOf<List<Ranked>>(emptyList()) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    val mapStyle by remember { c.settings.settings.map { it.mapStyle } }.collectAsStateWithLifecycle(initialValue = MapStyle.DARK)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = MyLocation.allowed(context)
    }
    LaunchedEffect(allowed) {
        if (allowed) MyLocation.get(context) { here = LatLon(it.latitude, it.longitude) }
    }
    // One request when there's a start and an end (the first fix is enough; a better fix later
    // doesn't ask again), and one per tap on Refresh.
    val haveHere = here != null
    LaunchedEffect(dest, refresh, haveHere) {
        val from = here ?: return@LaunchedEffect
        val to = dest ?: return@LaunchedEffect
        loading = true
        problem = null
        val a = log.routes(from, to.at)
        loading = false
        carRoutes = a.mode == TrafficRoutes.DRIVE
        ranked = RouteScore.rank(a.routes)
        picked = ranked.firstOrNull { !it.tooLong }?.index
        problem = when {
            a.problem != null -> a.problem
            a.status == 200 && a.routes.isEmpty() -> "Google has no route there"
            else -> null
        }
    }

    val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(28.6139, 77.2090), 11f) }
    // Frame every route once they're in; before that, where you are.
    LaunchedEffect(ranked, mapLoaded) {
        if (!mapLoaded) return@LaunchedEffect
        val points = ranked.flatMap { TrafficRoutes.decodePolyline(it.route.polyline) }
        if (points.size >= 2) {
            val b = LatLngBounds.builder().apply { points.forEach { include(it.g()) } }.build()
            runCatching { cam.animate(CameraUpdateFactory.newLatLngBounds(b, 120)) }
        }
    }
    LaunchedEffect(haveHere, mapLoaded) {
        val h = here ?: return@LaunchedEffect
        if (mapLoaded && ranked.isEmpty()) runCatching { cam.animate(CameraUpdateFactory.newLatLngZoom(h.g(), 13f)) }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding()) {
        ScreenHeader(
            "Keep moving",
            subtitle = "The clearest road, by Google's live traffic",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = RtDimens.xs),
            actions = {
                IconButton(onClick = { refresh++ }, enabled = dest != null && here != null && !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Check the traffic again", tint = RtColors.TextPrimary)
                }
            },
        )
        // Where to.
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Choose where you're riding to") { choosing = true }
                .padding(horizontal = RtDimens.screenPadding, vertical = 8.dp),
        ) {
            Text("Riding to", style = RtType.caption, color = RtColors.TextSecondary)
            Text(dest?.name ?: "Choose a place ›", style = RtType.bodyStrong, color = if (dest == null) RtColors.Primary else RtColors.TextPrimary, maxLines = 1)
        }

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
                // The app's own controls are drawn over the map instead of Google's.
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, compassEnabled = false),
                onMapLoaded = { mapLoaded = true },
                // Long-press anywhere: ride there.
                onMapLongClick = { p ->
                    val at = LatLon(p.latitude, p.longitude)
                    dest = Destination("Dropped pin", at)
                    scope.launch { c.places.placeAt(at.lat, at.lon)?.let { name -> if (dest?.at == at) dest = Destination(name, at) } }
                },
            ) {
                // The other routes first, grey, so the picked one draws on top.
                ranked.filter { it.index != picked }.forEach { r ->
                    Polyline(
                        points = TrafficRoutes.decodePolyline(r.route.polyline).map { it.g() },
                        color = OtherRouteColour,
                        width = 14f,
                        clickable = !r.tooLong,
                        onClick = { picked = r.index },
                    )
                }
                ranked.firstOrNull { it.index == picked }?.let { r ->
                    RouteScore.stretches(r.route).forEach { s ->
                        Polyline(points = s.points.map { it.g() }, color = s.speed.colour(), width = 18f, zIndex = 2f)
                    }
                }
                val pin = remember { dot(context, 26f, Teal, Color.White, 3f, centre = Color(0xFF0A0A0B)) }
                val me = remember { dot(context, 18f, Color.White, Teal, 4f) }
                dest?.let { d -> Marker(state = MarkerState(position = d.at.g()), title = d.name, icon = pin, anchor = Offset(0.5f, 0.5f), zIndex = 4f) }
                here?.let { h -> Marker(state = MarkerState(position = h.g()), title = "You", icon = me, anchor = Offset(0.5f, 0.5f), zIndex = 3f) }
            }
            MapStyleToggle(
                selected = mapStyle,
                onSelect = { st -> scope.launch { c.settings.setMapStyle(st) } },
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            here?.let { h ->
                IconButton(
                    onClick = { scope.launch { runCatching { cam.animate(CameraUpdateFactory.newLatLngZoom(h.g(), 15f)) } } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).background(RtColors.Background.copy(alpha = 0.85f), CircleShape),
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Show where I am", tint = RtColors.TextPrimary)
                }
            }
            if (loading) {
                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
        }

        // The routes, clearest first, and Start.
        Column(
            Modifier
                .fillMaxWidth()
                // At most this tall, so the map stays big.
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RtDimens.screenPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !allowed -> Message(
                    "Keep moving needs your location to find routes from where you are.",
                    action = "Allow location" to { permission.launch(Permissions.rideStartPermissions()) },
                )
                dest == null -> Message("Choose where you're riding to, or long-press the map.")
                here == null -> Message("Finding where you are…")
                problem != null -> Message(problem!!, action = "Try again" to { refresh++ })
                ranked.isNotEmpty() -> {
                    if (carRoutes) Text("Google has no two-wheeler routes here: these are car routes.", style = RtType.caption, color = RtColors.Warning)
                    val best = ranked.firstOrNull { !it.tooLong }?.index
                    ranked.forEach { r -> RouteCard(r, selected = r.index == picked, clearest = r.index == best, onClick = { if (!r.tooLong) picked = r.index }) }
                }
            }
            Text(
                "${log.checksThisMonth} traffic checks this month. Your ride's traffic log carries on as before.",
                style = RtType.caption,
                color = RtColors.TextTertiary,
            )
        }
        val route = ranked.firstOrNull { it.index == picked }
        PrimaryButton(
            if (starting) "Starting…" else "Start ride on this route",
            enabled = route != null && dest != null && !starting,
            large = true,
            onClick = {
                val d = dest ?: return@PrimaryButton
                starting = true
                scope.launch {
                    log.setDestination(com.ridetrack.app.keepmoving.Destinations.forSaving(d, c.places))
                    log.plan(route?.route)
                    val bike = QuickStart.bike(c.bikes.observeBikes().first(), c.settings.settings.first().selectedBikeId)
                    val ok = bike != null && QuickStart.start(c, bike)
                    starting = false
                    if (ok) onStarted() else problem = if (bike == null) "Add a bike first (Home › Add bike)" else "The ride didn't start: try again"
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = RtDimens.screenPadding).padding(bottom = 12.dp).navigationBarsPadding(),
        )
    }

    if (choosing) {
        DestinationPicker(
            onPick = { dest = it; choosing = false },
            onClear = null,
            onClose = { choosing = false },
        )
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

@Composable
private fun RouteCard(r: Ranked, selected: Boolean, clearest: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RtDimens.cardRadius)
    val km = { m: Int -> if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 10.0).roundToInt() * 10} m" }
    Column(
        Modifier
            .fillMaxWidth()
            .background(RtColors.Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) RtColors.Primary else RtColors.Hairline, shape)
            .clickable(role = Role.RadioButton, enabled = !r.tooLong, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(r.route.durationSec / 60.0).roundToInt()} min · ${km(r.route.distanceM)}",
                style = RtType.bodyStrong,
                color = if (r.tooLong) RtColors.TextTertiary else RtColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            when {
                r.tooLong -> Text("Over 3× the shortest", style = RtType.caption, color = RtColors.TextTertiary)
                clearest -> Text("Clearest", style = RtType.label, color = RtColors.Primary)
            }
        }
        r.route.description?.let { Text("via $it", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1) }
        Spacer(Modifier.height(6.dp))
        val col = r.colours
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (col.jamM == 0 && col.slowM == 0 && col.unknownM < 100) {
                Swatch(ClearColour, "Clear all the way")
            } else {
                if (col.jamM > 0) Swatch(JamColour, "${km(col.jamM)} jammed")
                if (col.slowM > 0) Swatch(SlowColour, "${km(col.slowM)} slow")
                if (col.unknownM >= 100) Swatch(NoDataColour, "${km(col.unknownM)} no data")
            }
        }
    }
}

@Composable
private fun Swatch(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colour, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = RtType.caption, color = RtColors.TextSecondary)
    }
}
