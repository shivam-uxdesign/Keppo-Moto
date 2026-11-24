package com.ridetrack.app.ui.keepmoving

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.keepmoving.Memory
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.MapStyleToggle
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.styleBuilder
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.Calendar

private const val CELLS = "your-roads"

/**
 * Step 4: what Keep moving has learned from your own rides, on the app's map (your data, so
 * MapTiler, not Google): each ~100 m of road you've ridden, coloured by how you usually move there
 * at the hour you pick: green moving, amber often crawling, red often stopped.
 */
@Composable
fun YourRoadsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val summary by c.trafficMemory.summary.collectAsStateWithLifecycle()
    val now = remember { Calendar.getInstance() }
    var hour by remember { mutableIntStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var weekend by remember { mutableStateOf(now.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }) }
    val bucket = (if (weekend) 24 else 0) + hour

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val mapStyle by remember { c.settings.settings.map { it.mapStyle } }.collectAsState(initial = MapStyle.DARK)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var framed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            map = m
        }
    }
    LaunchedEffect(map, mapStyle) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(styleBuilder(mapStyle)) { st ->
            st.addSource(GeoJsonSource(CELLS))
            st.addLayer(
                CircleLayer("your-roads-cells", CELLS).withProperties(
                    PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 1.5f), Expression.stop(16, 7f))),
                    PropertyFactory.circleColor(Expression.get("colour")),
                    PropertyFactory.circleOpacity(0.85f),
                ),
            )
            style = st
        }
    }
    // The cells at the picked hour (± 1), all directions together.
    LaunchedEffect(style, bucket, summary.cells) {
        val st = style ?: return@LaunchedEffect
        val stats = c.trafficMemory.snapshot()
        val hours = Memory.near(bucket).toSet()
        val cells = stats.entries.filter { it.key.bucket in hours }
            .groupBy { it.key.row to it.key.col }
            .map { (rc, list) -> rc to list.fold(Memory.Stat.EMPTY) { a, e -> a + e.value } }
        val features = cells.map { (rc, s) ->
            val p = Memory.centre(rc.first, rc.second)
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty(
                    "colour",
                    when {
                        s.stopShare >= 0.4 -> "#F43F5E"
                        s.stopShare + s.crawlShare >= 0.4 -> "#FBBF24"
                        else -> "#4ADE80"
                    },
                )
            }
        }
        st.getSourceAs<GeoJsonSource>(CELLS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        if (!framed && cells.isNotEmpty()) {
            framed = true
            val all = stats.keys.map { Memory.centre(it.row, it.col) }
            val b = LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            runCatching { map?.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 64)) }
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        ScreenHeader(
            "Your roads",
            subtitle = "How you usually move, from ${summary.rides} rides",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = RtDimens.xs),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            MapStyleToggle(
                selected = mapStyle,
                onSelect = { st -> scope.launch { c.settings.setMapStyle(st) } },
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            if (summary.cells == 0) {
                Text(
                    "Nothing learned yet: your rides show up here once they're read.",
                    style = RtType.body,
                    color = RtColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).background(RtColors.Surface, CircleShape).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = RtDimens.screenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(java.util.Locale.US, "%02d:00", hour), style = RtType.headline, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Chip("Weekday", !weekend) { weekend = false }
                Spacer(Modifier.width(8.dp))
                Chip("Weekend", weekend) { weekend = true }
            }
            Slider(
                value = hour.toFloat(),
                onValueChange = { hour = it.toInt().coerceIn(0, 23) },
                valueRange = 0f..23f,
                steps = 22,
                colors = SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = RtColors.Primary),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Key(Color(0xFF4ADE80), "Moving")
                Key(Color(0xFFFBBF24), "Often crawling")
                Key(Color(0xFFF43F5E), "Often stopped")
            }
            Text(
                "Your own yellow: Google's yellow meant stopping ${"%.0f".format(summary.yellowChance * 100)}% of the time for you" +
                    if (summary.yellowTrips == 0) " (1 in 5 until your rides with a traffic log teach it)" else " (${summary.yellowTrips} yellow stretches ridden)",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Text("Kept on this phone only. © MapTiler © OpenStreetMap contributors", style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.caption,
        color = if (on) RtColors.OnPrimary else RtColors.TextPrimary,
        modifier = Modifier
            .background(if (on) RtColors.Primary else RtColors.SurfaceRaised, CircleShape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun Key(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(colour, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = RtType.caption, color = RtColors.TextSecondary)
    }
}
