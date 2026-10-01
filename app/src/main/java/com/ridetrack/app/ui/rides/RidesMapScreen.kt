package com.ridetrack.app.ui.rides

import android.graphics.PointF
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.MapStyle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.GeoPoint
import com.ridetrack.app.ui.components.MapStyleToggle
import com.ridetrack.app.ui.components.MapTiler
import com.ridetrack.app.ui.components.hex
import com.ridetrack.app.ui.components.styleBuilder
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.DarkPalette
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class RidesMapUiState(
    val loading: Boolean = true,
    val bikes: List<Bike> = emptyList(),
    /** null = all bikes. */
    val bikeId: String? = null,
    val rides: List<Ride> = emptyList(),
    val routes: Map<String, List<GeoPoint>> = emptyMap(),
) {
    val mapped: List<Pair<Ride, List<GeoPoint>>>
        get() = rides.mapNotNull { r -> routes[r.id]?.takeIf { it.size >= 2 }?.let { r to it } }
}

class RidesMapViewModel(private val c: AppContainer) : ViewModel() {
    private val bikeFilter = MutableStateFlow<String?>(null)
    private val routes = MutableStateFlow<Map<String, List<GeoPoint>>>(emptyMap())
    private val requested = mutableSetOf<String>()

    val state: StateFlow<RidesMapUiState> =
        combine(c.rides.observeCompleted(), c.bikes.observeBikes(), bikeFilter, routes) { rides, bikes, bikeId, r ->
            load(rides.map { it.id })
            // A filter for a bike that no longer exists falls back to "all".
            val effective = bikeId?.takeIf { id -> bikes.size > 1 && bikes.any { it.id == id } }
            RidesMapUiState(
                loading = requested.any { it !in r },
                bikes = bikes,
                bikeId = effective,
                rides = rides.filter { effective == null || it.bikeId == effective },
                routes = r,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RidesMapUiState())

    private fun load(ids: List<String>) {
        val missing = ids.filter { requested.add(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch { missing.forEach { id -> routes.update { it + (id to c.routes.route(id)) } } }
    }

    fun setBike(id: String?) = bikeFilter.update { id }
}

private const val ALL_SOURCE = "all-routes"
private const val SELECTED_SOURCE = "selected-route"

/** Every recorded route on one map; tap a line to see that ride. */
@Composable
fun RidesMapScreen(onBack: () -> Unit, onOpenRide: (String) -> Unit) {
    val vm = appViewModel { RidesMapViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val mapped = s.mapped
    val selected = mapped.firstOrNull { it.first.id == selectedId }?.first
    LaunchedEffect(mapped) { if (selectedId != null && mapped.none { it.first.id == selectedId }) selectedId = null }

    Box(Modifier.fillMaxSize().background(RtColors.Background)) {
        AllRoutesMap(mapped, selectedId, onSelect = { selectedId = it }, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onBack)
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier
                        .background(RtColors.Background.copy(alpha = 0.8f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    val km = mapped.sumOf { it.first.stats.distanceM }
                    Text(
                        if (s.loading && mapped.isEmpty()) "Loading routes…" else "${mapped.size} rides · ${Format.distance(km)}",
                        style = RtType.bodyStrong,
                        color = RtColors.TextPrimary,
                    )
                }
            }
            // Only worth a filter when there's more than one bike.
            if (s.bikes.size > 1) {
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill("All bikes", s.bikeId == null) { vm.setBike(null) }
                    s.bikes.forEach { b -> FilterPill(b.displayName, s.bikeId == b.id) { vm.setBike(b.id) } }
                }
            }
        }

        if (!s.loading && mapped.isEmpty()) {
            Text(
                if (s.rides.isEmpty()) "No rides yet" else "No GPS routes recorded",
                style = RtType.body,
                color = RtColors.TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(RtColors.Background.copy(alpha = 0.8f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            selected != null,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val ride = selected ?: mapped.firstOrNull()?.first
            if (ride != null) SelectedRideCard(ride, s.bikes.firstOrNull { it.id == ride.bikeId }?.displayName.takeIf { s.bikes.size > 1 }) { onOpenRide(ride.id) }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(RtColors.Background.copy(alpha = 0.8f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = RtType.caption,
        color = if (selected) RtColors.OnPrimary else RtColors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RtColors.Primary else RtColors.Background.copy(alpha = 0.8f))
            .border(1.dp, if (selected) RtColors.Primary else RtColors.Hairline, RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SelectedRideCard(ride: Ride, bikeName: String?, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(22.dp))
            .background(RtColors.SurfaceRaised)
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ride.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ride.source == DataSourceKind.DEMO) {
                    Spacer(Modifier.width(8.dp))
                    DemoBadge()
                }
            }
            Text(
                listOfNotNull(Format.rideDate(ride.startTimeMillis), bikeName).joinToString(" · "),
                style = RtType.caption,
                color = RtColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.distance(ride.stats.distanceM)} · ${Format.duration(ride.durationMillis)} · max ${Format.speedWithUnit(ride.stats.maxSpeedMps)}",
                style = RtType.body,
                color = RtColors.TextPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open ride", tint = RtColors.Primary)
    }
}

@Composable
private fun AllRoutesMap(
    routes: List<Pair<Ride, List<GeoPoint>>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val select by rememberUpdatedState(onSelect)

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
            }
            m.addOnMapClickListener { latLng ->
                val px: PointF = m.projection.toScreenLocation(latLng)
                val slop = 18f * context.resources.displayMetrics.density
                val hit = m.queryRenderedFeatures(
                    android.graphics.RectF(px.x - slop, px.y - slop, px.x + slop, px.y + slop),
                    "all-routes",
                ).firstOrNull()
                select(hit?.getStringProperty("id"))
                true
            }
            map = m
        }
    }

    LaunchedEffect(map, mapStyle) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(styleBuilder(mapStyle)) { st ->
            st.addSource(GeoJsonSource(ALL_SOURCE))
            st.addSource(GeoJsonSource(SELECTED_SOURCE))
            st.addLayer(
                LineLayer("all-routes", ALL_SOURCE).withProperties(
                    PropertyFactory.lineColor(hex(DarkPalette.primary)),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineOpacity(0.55f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            st.addLayer(
                LineLayer("selected-casing", SELECTED_SOURCE).withProperties(
                    PropertyFactory.lineColor("#000000"),
                    PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
            st.addLayer(
                LineLayer("selected-route", SELECTED_SOURCE).withProperties(
                    PropertyFactory.lineColor(hex(DarkPalette.textPrimary)),
                    PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style = st
        }
    }

    LaunchedEffect(style, routes) {
        val st = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(ALL_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                routes.map { (ride, pts) ->
                    Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
                        addStringProperty("id", ride.id)
                    }
                },
            ),
        )
        val all = routes.flatMap { it.second }
        if (all.size >= 2) {
            val bounds = LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            mapView.post { runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96)) } }
        }
    }

    LaunchedEffect(style, routes, selectedId) {
        val st = style ?: return@LaunchedEffect
        val pts = routes.firstOrNull { it.first.id == selectedId }?.second
        st.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)?.setGeoJson(
            if (pts != null) {
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
            } else {
                FeatureCollection.fromFeatures(emptyList())
            },
        )
        st.getLayer("all-routes")?.setProperties(PropertyFactory.lineOpacity(if (pts != null) 0.3f else 0.55f))
    }

    Box(modifier.semantics { contentDescription = "Map of all ride routes" }) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (MapTiler.available) {
            MapStyleToggle(
                selected = mapStyle,
                onSelect = { st -> scope.launch { container.settings.setMapStyle(st) } },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = if (selectedId != null) 130.dp else 16.dp),
            )
        }
    }
}
