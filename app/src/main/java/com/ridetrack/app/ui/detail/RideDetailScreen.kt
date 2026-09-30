package com.ridetrack.app.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ridetrack.app.ui.components.ChartMark
import com.ridetrack.app.ui.components.OverviewStrip
import com.ridetrack.app.ui.components.ZoomChart
import com.ridetrack.telemetry.model.TelemetrySample
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
import androidx.compose.material3.IconButton
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
import com.ridetrack.app.ui.common.positionAt
import com.ridetrack.app.ui.common.presentation
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.HairlineDivider
import com.ridetrack.app.ui.components.RouteMap
import com.ridetrack.app.ui.components.MapPin
import com.ridetrack.app.ui.moments.MomentStrip
import com.ridetrack.app.ui.moments.MomentsDiagnostics
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.moments.rememberMoments
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.Shimmer
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.DataSourceKind

@Composable
fun RideDetailScreen(rideId: String, onBack: () -> Unit, onShare: () -> Unit, onOpenMoment: (String) -> Unit) {
    val moments = rememberMoments(rideId)
    val vm = appViewModel(key = "detail-$rideId") { RideDetailViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scrub by vm.scrub.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val play by vm.playback.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause() }

    val ride = s.ride
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPaddingWide),
    ) {
        ScreenHeader(
            title = ride?.name ?: "Ride",
            subtitle = ride?.let { "${Format.distance(it.stats.distanceM)} · ${Format.duration(it.durationMillis)} · ${Format.rideDate(it.startTimeMillis)}" },
            onBack = onBack,
        ) {
            if (ride?.source == DataSourceKind.DEMO) DemoBadge()
            if (ride != null) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Share ride", tint = RtColors.TextPrimary)
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Ride options", tint = RtColors.TextPrimary)
                }
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
        if (ride == null) {
            if (!s.loading) EmptyState("Ride not found", "This ride may have been deleted.")
            return@Column
        }

        val data = s.data
        val samples = data?.samples.orEmpty()
        val idx = scrub?.let { f -> data?.index(f) }
        val sample = idx?.let { samples.getOrNull(it) }
        // The moment nearest the timeline position is highlighted in the strip.
        val nearMoment = sample?.let { sm ->
            moments.minByOrNull { kotlin.math.abs(it.timeMillis - sm.timeMillis) }?.takeIf { kotlin.math.abs(it.timeMillis - sm.timeMillis) <= NEAR_MOMENT_MS }
        }
        val scrubAndPause: (Float) -> Unit = { f -> vm.pause(); vm.scrubTo(f) }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(RtDimens.xs))
            if (data == null) {
                Shimmer(Modifier.fillMaxWidth().height(260.dp), radius = RtDimens.cardRadius)
            } else {
                RouteMap(
                    route = data.route,
                    marker = sample?.let { samples.positionAt(it.timeMillis) },
                    progress = idx?.let { data.routeCountAt.getOrNull(it) },
                    pins = moments.mapNotNull { m -> if (m.latitude != null && m.longitude != null) MapPin(m.id, m.latitude, m.longitude, m.thumb, momentColor(m)) else null },
                    onPinClick = onOpenMoment,
                    interactive = true,
                    zoomButtons = true,
                    threeD = play.threeD,
                    bearing = idx?.let { data.bearingAt(it) },
                    playing = play.playing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                ) {
                    ViewToggle(play.threeD, onChange = { haptics.tick(); vm.setThreeD(it) }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                    if (sample != null) {
                        Text(
                            "${Format.timeOfDay(sample.timeMillis)} · ${Format.speedWithUnit(sample.speedMps)}",
                            style = RtType.caption.copy(fontFeatureSettings = "tnum"),
                            color = RtColors.TextPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .background(RtColors.Background.copy(alpha = 0.75f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (data.route.size >= 2 && data.durationMillis > 0) {
                        PlayControls(
                            playing = play.playing,
                            speedLabel = play.speedLabel,
                            onToggle = { haptics.tick(); vm.togglePlay() },
                            onSpeed = vm::cycleSpeed,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        )
                    }
                }
            }

            // Headline: distance, moving time, top speed (tap to find it).
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Headline("Distance", Format.distanceValue(ride.stats.distanceM), "km", Modifier.weight(1f))
                Headline("Moving", Format.duration(ride.stats.movingMillis), null, Modifier.weight(1f))
                Headline(
                    "Top speed", Format.speedKmh(ride.stats.maxSpeedMps), "km/h", Modifier.weight(1f), color = RtColors.GForce,
                    onClick = data?.takeIf { it.samples.size >= 2 }?.let { d -> { vm.jumpToIndex(d.initialIndex) } },
                )
            }

            if (moments.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                MomentStrip(moments, onOpen = onOpenMoment, selectedId = nearMoment?.id)
            }
            if (BuildConfig.DEBUG) MomentsDiagnostics(rideId)

            Spacer(Modifier.height(8.dp))
            RideDynamics(
                ride,
                onLeft = data?.maxLeftIndex?.let { i -> { vm.jumpToIndex(i) } },
                onRight = data?.maxRightIndex?.let { i -> { vm.jumpToIndex(i) } },
                onBrake = data?.hardestBrakeIndex?.let { i -> { vm.jumpToIndex(i) } },
                onAccel = data?.strongestAccelIndex?.let { i -> { vm.jumpToIndex(i) } },
            )

            // A slim live readout right above the graphs.
            Spacer(Modifier.height(22.dp))
            LiveReadout(sample)
            Spacer(Modifier.height(12.dp))
            SegmentedTabs(chart, onSelect = { haptics.tick(); vm.selectChart(it) })
            Spacer(Modifier.height(10.dp))

            if (data == null) {
                Shimmer(Modifier.fillMaxWidth().height(126.dp))
            } else if (samples.size < 2) {
                Text("Not enough telemetry was recorded for charts.", style = RtType.caption, color = RtColors.TextSecondary)
            } else {
                val (series, color, negative) = when (chart) {
                    ChartKind.SPEED -> Triple(data.speed, RtColors.Primary, null)
                    ChartKind.LEAN -> Triple(data.lean, RtColors.Right, RtColors.Left)
                    ChartKind.G -> Triple(data.gForce, RtColors.GForce, null)
                    ChartKind.ELEVATION -> Triple(data.elevation, RtColors.Left, null)
                }
                val marks = remember(data, moments) {
                    data.track.events.filterNot { it.type.isTurn }.map { e -> ChartMark(data.fractionAt(e.timeMillis), e.presentation().color) } +
                        moments.map { m -> ChartMark(data.fractionAt(m.timeMillis), RtColors.TextPrimary, top = true) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(RtColors.Surface),
                ) {
                    ZoomChart(
                        series = series, color = color, negativeColor = negative,
                        scrub = scrub, window = window, onScrub = scrubAndPause, onWindow = vm::setWindow,
                        minSpan = vm.minSpan(), marks = marks, height = 126.dp,
                        unavailableText = if (chart == ChartKind.LEAN) "Lean wasn't recorded for this ride (mount not calibrated or no gyroscope)." else "Not recorded for this ride.",
                    )
                }
                val winStart = data.startMillis + (window.start * data.durationMillis).toLong()
                val winEnd = data.startMillis + (window.end * data.durationMillis).toLong()
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.timeOfDay(winStart), style = RtType.caption, color = RtColors.TextTertiary)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (window.isZoomed) {
                            Text(
                                "Reset zoom",
                                style = RtType.caption,
                                color = RtColors.Primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, RtColors.Primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    .clickable(role = Role.Button) { vm.resetZoom() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        } else {
                            Text("Pinch or double-tap to zoom", style = RtType.caption, color = RtColors.TextTertiary)
                        }
                    }
                    Text(Format.timeOfDay(winEnd), style = RtType.caption, color = RtColors.TextTertiary)
                }
                // The whole ride in miniature, only while zoomed in.
                AnimatedVisibility(window.isZoomed) {
                    OverviewStrip(series, window, scrub, onWindow = vm::setWindow, minSpan = vm.minSpan(), color = color, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // Events: collapsed by default; tap one to find it on the timeline.
            val allEvents = data?.track?.events.orEmpty()
            val events = allEvents.filter { showTurns || !it.type.isTurn }
            Spacer(Modifier.height(22.dp))
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
            Spacer(Modifier.height(RtDimens.lg))
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

/** Pill segmented control; the selected segment is an inverse (white) pill. */
@Composable
private fun SegmentedTabs(selected: ChartKind, onSelect: (ChartKind) -> Unit) {
    Row(
        Modifier
            .background(RtColors.Surface, RoundedCornerShape(50))
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChartKind.entries.forEach { kind ->
            val isSel = kind == selected
            val bg by animateColorAsState(if (isSel) RtColors.Inverse else RtColors.Surface, label = "tabBg")
            val fg by animateColorAsState(if (isSel) RtColors.OnInverse else RtColors.TextSecondary, label = "tabFg")
            Text(
                kind.label,
                style = RtType.bodyStrong.copy(fontSize = RtType.body.fontSize),
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(kind) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** How close (in time) a moment must be to the timeline position to be highlighted. */
private const val NEAR_MOMENT_MS = 90_000L

@Composable
private fun Headline(label: String, value: String, unit: String?, modifier: Modifier, color: androidx.compose.ui.graphics.Color = RtColors.TextPrimary, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show on timeline", onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(if (onClick != null) "$label ›" else label, style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = RtType.metricM.copy(fontWeight = FontWeight.Light), color = color)
            if (unit != null) Text(" $unit", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Time · speed · lean · G · elevation at the timeline position, on one slim line. */
@Composable
private fun LiveReadout(sample: TelemetrySample?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(RtColors.Surface)
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val style = RtType.caption.copy(fontFeatureSettings = "tnum")
        Text(sample?.let { Format.timeOfDay(it.timeMillis) } ?: Format.DASH, style = style, color = RtColors.TextSecondary)
        Text(Format.speedWithUnit(sample?.speedMps), style = style, color = RtColors.Primary)
        Text(Format.lean(sample?.leanDeg), style = style, color = leanColor(sample?.leanDeg))
        Text(Format.g(sample?.combinedG), style = style, color = RtColors.GForce)
        Text(Format.altitude(sample?.altitudeM), style = style, color = RtColors.TextPrimary)
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
