package com.ridetrack.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.export.ExportShare
import com.ridetrack.app.ui.appContainer
import kotlinx.coroutines.launch
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.common.RideDynamics
import com.ridetrack.app.ui.common.isTurn
import com.ridetrack.app.ui.common.presentation
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.HairlineDivider
import com.ridetrack.app.ui.components.RouteMap
import com.ridetrack.app.ui.components.MapPin
import com.ridetrack.app.ui.moments.MomentsDiagnostics
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.components.Shimmer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.DataSourceKind
import androidx.compose.foundation.layout.RowScope
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import com.ridetrack.app.ui.common.easeAngle
import com.ridetrack.app.ui.common.indexAt
import com.ridetrack.app.ui.common.positionAtSmooth
import com.ridetrack.app.ui.common.valueAtSmooth
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.exp

@Composable
fun RideDetailScreen(rideId: String, onBack: () -> Unit, onShare: () -> Unit, onOpenMoment: (String) -> Unit) {
    val vm = appViewModel(key = "detail-$rideId") { RideDetailViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val moments by vm.moments.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val play by vm.playback.collectAsStateWithLifecycle()
    val active by vm.activeMoment.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    // Full-screen map: the timeline floats over the bottom of it as a sheet.
    var full by rememberSaveable { mutableStateOf(false) }
    var sheetPx by remember { mutableIntStateOf(0) }
    BackHandler(enabled = full) { full = false }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause() }

    val ride = s.ride
    val data = s.data

    // What's on screen follows the playhead every display frame: replay advances by the frame's
    // real time, and a scrub or jump glides there instead of snapping. The heading eases too.
    var shownTime by remember { mutableDoubleStateOf(Double.NaN) }
    var shownHeading by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        var last = 0L
        while (true) {
            val target = vm.time.value
            val settled = target == null || (!vm.playback.value.playing && shownTime == target && headingSettled(d, shownTime, shownHeading))
            if (settled) {
                // Nothing moving: wait for the playhead or playback to change.
                combine(vm.time, vm.playback) { t, p -> t != target || p.playing }.first { it }
                last = 0L
            }
            withFrameNanos { nanos ->
                val dt = if (last == 0L) 16.0 else ((nanos - last) / 1_000_000.0).coerceAtMost(MAX_FRAME_MS)
                last = nanos
                vm.advance(dt)
                val t = vm.time.value ?: return@withFrameNanos
                shownTime = when {
                    shownTime.isNaN() || vm.playback.value.playing -> t
                    abs(t - shownTime) < 20.0 -> t
                    else -> shownTime + (t - shownTime) * (1.0 - exp(-dt / GLIDE_MS))
                }
                val heading = d.bearingAtTime(shownTime)
                if (heading != null) {
                    shownHeading = if (shownHeading.isNaN()) heading else easeAngle(shownHeading, heading, (1.0 - exp(-dt / HEADING_EASE_MS)).toFloat())
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sheetHeight = if (full) with(density) { sheetPx.toDp() } else 0.dp
        val mapHeight by animateDpAsState(if (full) maxHeight else maxHeight * MAP_SHARE, tween(FULL_ANIM_MS), label = "mapHeight")
        // The timeline: under the map normally, a sheet over it in full screen.
        val tracks: @Composable (Modifier) -> Unit = { mod ->
            val time = shownTime
            if (ride != null && data != null && !time.isNaN() && data.samples.size >= 2) {
                RideTracks(
                    data = data,
                    ride = ride,
                    moments = moments,
                    time = time,
                    fraction = data.fractionOf(time),
                    lean = data.samples.valueAtSmooth(time) { it.leanDeg },
                    window = window,
                    chart = chart,
                    minSpan = vm.minSpan(),
                    onCycleChart = { haptics.tick(); vm.cycleChart() },
                    onScrub = { f -> vm.pause(); vm.scrubTo(f) },
                    onWindow = vm::setWindow,
                    onResetZoom = vm::resetZoom,
                    onJump = vm::jumpToIndex,
                    onJumpToTime = vm::jumpToTime,
                    onOpenMoment = { id -> vm.pause(); onOpenMoment(id) },
                    modifier = mod.padding(horizontal = RtDimens.screenPaddingWide).padding(top = 6.dp, bottom = 10.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
                val time = shownTime
                if (data != null && !time.isNaN()) {
                    val samples = data.samples
                    val idx = samples.indexAt(time.toLong())
                    RouteMap(
                        route = data.route,
                        marker = samples.positionAtSmooth(time),
                        progress = data.routeCountAt.getOrNull(idx),
                        pins = moments.mapNotNull { m -> if (m.latitude != null && m.longitude != null) MapPin(m.id, m.latitude, m.longitude, m.thumb, momentColor(m)) else null },
                        onPinClick = onOpenMoment,
                        interactive = true,
                        threeD = play.threeD,
                        bearing = shownHeading.takeIf { !it.isNaN() },
                        playing = play.playing,
                        bikeLean = (samples.valueAtSmooth(time) { it.leanDeg } ?: 0.0).toFloat(),
                        corner = 0.dp,
                        controlsTop = MAP_HEADER_HEIGHT,
                        bottomInset = sheetHeight,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ViewToggle(
                            play.threeD,
                            onChange = { haptics.tick(); vm.setThreeD(it) },
                            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = MAP_HEADER_HEIGHT, end = 10.dp, start = 10.dp, bottom = 10.dp).padding(top = 10.dp),
                        )
                        RoundButton(
                            if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (full) "Exit full screen" else "Full-screen map",
                            onClick = { haptics.tick(); full = !full },
                            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = MAP_HEADER_HEIGHT + 60.dp, end = 10.dp),
                        )
                        val sample = samples.getOrNull(idx)
                        if (sample != null) {
                            Text(
                                "${Format.timeOfDay(time.toLong())} · ${Format.speedWithUnit(sample.speedMps)}",
                                style = RtType.caption.copy(fontFeatureSettings = "tnum"),
                                color = RtColors.TextPrimary,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(bottom = sheetHeight)
                                    .padding(10.dp)
                                    .background(RtColors.Background.copy(alpha = 0.75f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        if (data.route.size >= 2 && data.durationMillis > 0) {
                            PlayControls(
                                playing = play.playing,
                                speedLabel = if (active != null) "1×" else play.speedLabel,
                                onToggle = { haptics.tick(); vm.togglePlay() },
                                onSpeed = vm::cycleSpeed,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = sheetHeight).padding(10.dp),
                            )
                        }
                    }
                } else {
                    Shimmer(Modifier.fillMaxSize(), radius = 0.dp)
                }
                MapHeader(
                    title = ride?.name ?: "Ride",
                    subtitle = ride?.let {
                        listOf(
                            Format.rideDate(it.startTimeMillis),
                            Format.distance(it.stats.distanceM),
                            Format.duration(it.durationMillis),
                            "${Format.speedKmh(it.stats.maxSpeedMps)} km/h top",
                        ).joinToString(" · ")
                    },
                    onBack = onBack,
                ) {
                    if (ride?.source == DataSourceKind.DEMO) DemoBadge()
                    if (ride != null) {
                        RoundButton(Icons.Outlined.IosShare, "Share ride", onClick = onShare)
                        Box {
                            RoundButton(Icons.Outlined.MoreVert, "Ride options", onClick = { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = RtColors.SurfaceRaised) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                                if (BuildConfig.DEBUG) {
                                    // BETA TOOL: remove together with data/export.
                                    DropdownMenuItem(
                                        text = { Text("Export ride (beta)") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch { runCatching { ExportShare.exportAndShare(context, container, listOf(ride.id)) } }
                                        },
                                    )
                                }
                                DropdownMenuItem(text = { Text("Delete", color = RtColors.Error) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    }
                }
            }
            if (ride == null) {
                if (!s.loading) EmptyState("Ride not found", "This ride may have been deleted.")
                return@Column
            }

            if (full) return@Column
            // The timeline, pinned under the map.
            val time = shownTime
            if (data == null || time.isNaN()) {
                Shimmer(Modifier.padding(horizontal = RtDimens.screenPaddingWide, vertical = 12.dp).fillMaxWidth().height(180.dp))
            } else if (data.samples.size < 2) {
                Text("Not enough telemetry was recorded for a timeline.", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(RtDimens.screenPaddingWide))
            } else {
                tracks(Modifier)
            }
            HairlineDivider()

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = RtDimens.screenPaddingWide),
            ) {
                Spacer(Modifier.height(6.dp))
                RideDynamics(
                    ride,
                    onLeft = data?.maxLeftIndex?.let { i -> { vm.jumpToIndex(i) } },
                    onRight = data?.maxRightIndex?.let { i -> { vm.jumpToIndex(i) } },
                    onBrake = data?.hardestBrakeIndex?.let { i -> { vm.jumpToIndex(i) } },
                    onAccel = data?.strongestAccelIndex?.let { i -> { vm.jumpToIndex(i) } },
                    onTopSpeed = data?.takeIf { it.samples.size >= 2 }?.let { d -> { vm.jumpToIndex(d.initialIndex) } },
                )

                // Events: collapsed by default; tap one to find it on the timeline.
                val allEvents = data?.track?.events.orEmpty()
                val events = allEvents.filter { showTurns || !it.type.isTurn }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(RtColors.Surface)
                        .clickable(role = Role.Button, onClickLabel = if (eventsOpen) "Hide events" else "Show events") { eventsOpen = !eventsOpen }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Events", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text("  ·  ${events.size}", style = RtType.body, color = RtColors.TextTertiary, modifier = Modifier.weight(1f))
                    Text(if (eventsOpen) "Hide" else "Show all", style = RtType.caption, color = RtColors.TextSecondary)
                    val turn by animateFloatAsState(if (eventsOpen) 180f else 0f, label = "chevron")
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.padding(start = 6.dp).size(20.dp).rotate(turn))
                }
                AnimatedVisibility(eventsOpen) {
                    Column {
                        if (allEvents.any { it.type.isTurn }) {
                            TextButton(onClick = { showTurns = !showTurns }) {
                                Text(if (showTurns) "Hide turns" else "Show turns", style = RtType.caption, color = RtColors.Primary)
                            }
                        }
                        if (events.isEmpty()) {
                            Text("No events recorded.", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                        }
                        events.forEachIndexed { i, e ->
                            val p = e.presentation()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { vm.jumpToTime(e.timeMillis) }
                                    .padding(vertical = 12.dp)
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(36.dp).background(RtColors.Surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Icon(p.icon, contentDescription = null, tint = p.color, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(RtDimens.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                                    if (!p.detail.isNullOrBlank()) Text(p.detail, style = RtType.caption, color = RtColors.TextSecondary)
                                }
                                Text(Format.timeOfDay(e.timeMillis), style = RtType.caption, color = RtColors.TextSecondary)
                            }
                            if (i < events.lastIndex) HairlineDivider()
                        }
                    }
                }
                if (BuildConfig.DEBUG) MomentsDiagnostics(rideId)
                Spacer(Modifier.height(RtDimens.lg))
            }
        }

        if (full) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { sheetPx = it.height }
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(RtColors.Background.copy(alpha = 0.86f))
                    .border(1.dp, RtColors.Hairline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .navigationBarsPadding(),
            ) { tracks(Modifier.padding(top = 4.dp)) }
        }

        // A moment reached during replay pops up over everything.
        val popup = active
        if (popup != null && !shownTime.isNaN()) {
            MomentPopup(
                popup,
                time = shownTime,
                playing = play.playing,
                onSkip = { haptics.tick(); vm.skipMoment() },
                onOpen = { vm.pause(); onOpenMoment(popup.id) },
            )
        }
    }

    if (renaming && ride != null) {
        var name by remember { mutableStateOf(ride.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename ride") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ride?") },
            text = { Text("This ride and all of its telemetry will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

/** 2D overview or 3D follow view. */
@Composable
private fun ViewToggle(threeD: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(RtColors.Background.copy(alpha = 0.75f), RoundedCornerShape(50)).padding(3.dp)) {
        listOf(false to "2D", true to "3D").forEach { (on, label) ->
            val sel = on == threeD
            Text(
                label,
                style = RtType.caption,
                color = if (sel) RtColors.OnInverse else RtColors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) RtColors.Inverse else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = sel, role = Role.Tab, onClick = { onChange(on) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Replay on the map: speed chip (1×/4×/16×/60×) and play/pause. */
@Composable
private fun PlayControls(playing: Boolean, speedLabel: String, onToggle: () -> Unit, onSpeed: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            speedLabel,
            style = RtType.caption.copy(fontFeatureSettings = "tnum"),
            color = RtColors.TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(RtColors.Background.copy(alpha = 0.75f))
                .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClickLabel = "Change replay speed", onClick = onSpeed)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(RtColors.Inverse)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = if (playing) "Pause replay" else "Play ride" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = RtColors.OnInverse, modifier = Modifier.size(22.dp))
        }
    }
}

/** Title and actions over the top of the map, on a soft scrim so they read on any basemap. */
@Composable
private fun MapHeader(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(RtColors.Background.copy(alpha = 0.85f), Color.Transparent)))
            .statusBarsPadding()
            .height(MAP_HEADER_HEIGHT)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.bodyStrong.copy(fontSize = 17.sp), color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(RtColors.Background.copy(alpha = 0.7f))
            .border(1.dp, RtColors.Hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(19.dp))
    }
}

/** True when the shown heading has caught up with the direction of travel. */
private fun headingSettled(d: TrackData, time: Double, heading: Float): Boolean {
    val target = d.bearingAtTime(time) ?: return true
    if (heading.isNaN()) return false
    return abs(((target - heading) % 360f + 540f) % 360f - 180f) < 0.5f
}

private const val MAP_SHARE = 0.42f
private const val FULL_ANIM_MS = 300
private val MAP_HEADER_HEIGHT = 56.dp
/** How quickly the view glides to a new playhead position (time constant, ms). */
private const val GLIDE_MS = 90.0
private const val HEADING_EASE_MS = 220.0
private const val MAX_FRAME_MS = 100.0
