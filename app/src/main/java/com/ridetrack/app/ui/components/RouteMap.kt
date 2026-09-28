package com.ridetrack.app.ui.components

import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.ui.appContainer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtMotion
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberReduceMotion
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import androidx.compose.runtime.rememberUpdatedState
import com.ridetrack.app.moments.ClipWriter
import com.ridetrack.app.ui.moments.circlePin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class GeoPoint(val latitude: Double, val longitude: Double)

private const val ROUTE_SOURCE = "route-source"
private const val ENDS_SOURCE = "ends-source"
private const val MARKER_SOURCE = "marker-source"
private const val PROGRESS_SOURCE = "progress-source"
private const val PINS_SOURCE = "moment-pins"
private const val PINS_LAYER = "moment-pins"

/** A moment on the map. */
data class MapPin(val id: String, val latitude: Double, val longitude: Double, val thumb: java.io.File?, val color: androidx.compose.ui.graphics.Color)

/**
 * Fallback dark raster basemap (OpenStreetMap data, CARTO tiles) used when no MapTiler key
 * is configured. Tiles need a connection; the route is local data and always draws.
 */
private val DARK_STYLE = """
{
  "version": 8,
  "sources": {
    "basemap": {
      "type": "raster",
      "tiles": [
        "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors © CARTO"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#0A0A0B" } },
    { "id": "basemap", "type": "raster", "source": "basemap" }
  ]
}
""".trimIndent()

internal fun hex(c: androidx.compose.ui.graphics.Color): String {
    val argb = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    return String.format("#%06X", 0xFFFFFF and argb)
}

/**
 * MapLibre route map. [route] should already be filtered to valid fixes; [marker] is the
 * current scrub/replay position.
 */
@Composable
fun RouteMap(
    route: List<GeoPoint>,
    modifier: Modifier = Modifier,
    marker: GeoPoint? = null,
    interactive: Boolean = false,
    followMarker: Boolean = false,
    /** Number of route points already travelled (scrub/replay); the rest is dimmed. */
    progress: Int? = null,
    /** Draw the route progressively once when it first appears (ride summary). */
    animateDraw: Boolean = false,
    /** Round thumbnails where moments happened; tapping one calls [onPinClick]. */
    pins: List<MapPin> = emptyList(),
    onPinClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
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

    val container = appContainer()
    val mapStyle by remember { container.settings.settings.map { it.mapStyle } }.collectAsState(initial = MapStyle.DARK)
    val scope = rememberCoroutineScope()

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                setAllGesturesEnabled(interactive)
            }
            map = m
        }
    }

    LaunchedEffect(map, mapStyle) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(styleBuilder(mapStyle)) { s ->
            addRouteLayers(s)
            style = s
        }
    }

    val reduceMotion = rememberReduceMotion()
    val drawAnim = remember(route) { Animatable(if (animateDraw && !reduceMotion) 0f else 1f) }
    LaunchedEffect(style, route) {
        if (style != null && animateDraw && !reduceMotion && drawAnim.value < 1f) {
            kotlinx.coroutines.delay(300)
            drawAnim.animateTo(1f, tween(RtMotion.ROUTE_DRAW, easing = FastOutSlowInEasing))
        }
    }
    val drawnCount = if (drawAnim.value < 1f) (route.size * drawAnim.value).toInt().coerceAtLeast(1) else null
    val effectiveProgress = drawnCount ?: progress

    LaunchedEffect(style, route, effectiveProgress) {
        val s = style ?: return@LaunchedEffect
        val done = effectiveProgress?.coerceIn(0, route.size)
        val pts = route.take(done ?: 0).map { Point.fromLngLat(it.longitude, it.latitude) }
        s.getSourceAs<GeoJsonSource>(PROGRESS_SOURCE)?.setGeoJson(
            if (pts.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts)))
            else FeatureCollection.fromFeatures(emptyList()),
        )
        // While a progress is shown, the untravelled route is dimmed; while drawing, hidden.
        val baseColor = if (done == null) hex(RtColors.Primary) else "#2C4F50"
        s.getLayer("route")?.setProperties(
            PropertyFactory.lineColor(baseColor),
            PropertyFactory.lineOpacity(if (drawnCount != null) 0f else 1f),
        )
        s.getLayer("route-casing")?.setProperties(PropertyFactory.lineOpacity(if (drawnCount != null) 0f else 0.6f))
    }

    LaunchedEffect(style, route) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val points = route.map { Point.fromLngLat(it.longitude, it.latitude) }
        s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
            if (points.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
            else FeatureCollection.fromFeatures(emptyList()),
        )
        s.getSourceAs<GeoJsonSource>(ENDS_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(points.firstOrNull(), points.lastOrNull()).map { Feature.fromGeometry(it) }),
        )
        mapView.post { fitCamera(m, route) }
    }

    LaunchedEffect(style, marker) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(marker?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }),
            ),
        )
        if (followMarker && marker != null) {
            map?.moveCamera(CameraUpdateFactory.newLatLng(LatLng(marker.latitude, marker.longitude)))
        }
    }

    val pinClick by rememberUpdatedState(onPinClick)
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        m.addOnMapClickListener { latLng ->
            val px = m.projection.toScreenLocation(latLng)
            val slop = 20f * context.resources.displayMetrics.density
            val hit = m.queryRenderedFeatures(android.graphics.RectF(px.x - slop, px.y - slop, px.x + slop, px.y + slop), PINS_LAYER)
                .firstOrNull()?.getStringProperty("id")
            if (hit != null) pinClick(hit)
            hit != null
        }
    }

    LaunchedEffect(style, pins) {
        val s = style ?: return@LaunchedEffect
        val sizePx = (40 * context.resources.displayMetrics.density).toInt()
        val ringPx = 2.5f * context.resources.displayMetrics.density
        val images = withContext(Dispatchers.IO) {
            pins.associate { pin ->
                val thumb = pin.thumb?.let { runCatching { ClipWriter.load(it, 160) }.getOrNull() }
                pin.id to circlePin(thumb, pin.color, sizePx, ringPx)
            }
        }
        images.forEach { (id, bmp) -> s.addImage("pin-$id", bmp) }
        s.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                pins.map { pin ->
                    Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude)).apply {
                        addStringProperty("id", pin.id)
                        addStringProperty("icon", "pin-${pin.id}")
                    }
                },
            ),
        )
    }

    Box(
        modifier
            .clip(RoundedCornerShape(RtDimens.cardRadius))
            .background(RtColors.Surface)
            .semantics { contentDescription = "Route map" },
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (MapTiler.available) {
            MapStyleToggle(
                selected = mapStyle,
                onSelect = { st -> scope.launch { container.settings.setMapStyle(st) } },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            )
        }
        if (route.size < 2) {
            Text(
                "No GPS route recorded",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(RtColors.Background.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

internal object MapTiler {
    val available: Boolean get() = BuildConfig.MAPTILER_KEY.isNotBlank()

    fun styleUrl(style: MapStyle): String {
        val id = when (style) {
            MapStyle.DARK -> "streets-v2-dark"
            MapStyle.SATELLITE -> "hybrid"
        }
        return "https://api.maptiler.com/maps/$id/style.json?key=${BuildConfig.MAPTILER_KEY}"
    }
}

/** MapTiler vector/satellite styles when a key is configured; otherwise a keyless dark basemap. */
internal fun styleBuilder(style: MapStyle): Style.Builder =
    if (MapTiler.available) Style.Builder().fromUri(MapTiler.styleUrl(style)) else Style.Builder().fromJson(DARK_STYLE)

private fun addRouteLayers(s: Style) {
    s.addSource(GeoJsonSource(ROUTE_SOURCE))
    s.addSource(GeoJsonSource(ENDS_SOURCE))
    s.addSource(GeoJsonSource(MARKER_SOURCE))
    s.addSource(GeoJsonSource(PROGRESS_SOURCE))
    s.addLayer(
        LineLayer("route-casing", ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor("#000000"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineOpacity(0.6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    s.addLayer(
        LineLayer("route", ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(hex(RtColors.Primary)),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    s.addLayer(
        LineLayer("progress", PROGRESS_SOURCE).withProperties(
            PropertyFactory.lineColor(hex(RtColors.Primary)),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    s.addLayer(
        CircleLayer("ends", ENDS_SOURCE).withProperties(
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleColor(hex(RtColors.TextPrimary)),
            PropertyFactory.circleStrokeColor("#000000"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    s.addSource(GeoJsonSource(PINS_SOURCE))
    s.addLayer(
        SymbolLayer(PINS_LAYER, PINS_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    s.addLayer(
        CircleLayer("marker", MARKER_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(hex(RtColors.GForce)),
            PropertyFactory.circleStrokeColor("#000000"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

@Composable
internal fun MapStyleToggle(selected: MapStyle, onSelect: (MapStyle) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(RtColors.Background.copy(alpha = 0.75f), RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        MapStyle.entries.forEach { st ->
            val isSelected = st == selected
            Text(
                st.label,
                style = RtType.caption,
                color = if (isSelected) RtColors.OnPrimary else RtColors.TextPrimary,
                modifier = Modifier
                    .background(if (isSelected) RtColors.Primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(st) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun fitCamera(map: MapLibreMap, route: List<GeoPoint>) {
    when {
        route.isEmpty() -> Unit
        route.size == 1 || route.all { it == route.first() } -> {
            val p = route.first()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 15.0))
        }
        else -> {
            val bounds = LatLngBounds.Builder().apply { route.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64)) }
        }
    }
}
