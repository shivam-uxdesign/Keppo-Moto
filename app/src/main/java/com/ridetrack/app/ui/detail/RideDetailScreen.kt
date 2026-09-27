package com.ridetrack.app.ui.detail

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.ridetrack.app.ui.common.Maneuvers
import com.ridetrack.app.ui.common.RideDynamics
import com.ridetrack.app.ui.common.TimeBreakdown
import com.ridetrack.app.ui.common.isTurn
import com.ridetrack.app.ui.common.positionAt
import com.ridetrack.app.ui.common.presentation
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.HairlineDivider
import com.ridetrack.app.ui.components.LineChart
import com.ridetrack.app.ui.components.RouteMap
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.components.Shimmer
import com.ridetrack.app.ui.components.Stat
import com.ridetrack.app.ui.components.StatRow
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.DataSourceKind
import kotlin.math.roundToInt

@Composable
fun RideDetailScreen(rideId: String, onBack: () -> Unit, onReplay: () -> Unit, onShare: () -> Unit) {
    val vm = appViewModel(key = "detail-$rideId") { RideDetailViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scrub by vm.scrub.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }

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
        val idx = scrub?.let { f -> (f * (samples.size - 1)).roundToInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0)) }
        val sample = idx?.let { samples.getOrNull(it) }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(RtDimens.xs))
            // Map with the travelled part of the route highlighted.
            if (data == null) {
                Shimmer(Modifier.fillMaxWidth().height(250.dp), radius = RtDimens.cardRadius)
            } else {
                Box {
                    RouteMap(
                        route = data.route,
                        marker = sample?.let { samples.positionAt(it.timeMillis) },
                        progress = idx?.let { data.routeCountAt.getOrNull(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                    )
                    if (sample != null) {
                        Text(
                            Format.timeOfDay(sample.timeMillis),
                            style = RtType.caption.copy(fontFeatureSettings = "tnum"),
                            color = RtColors.TextPrimary,
                            modifier = Modifier
                                .padding(start = 12.dp, top = 12.dp)
                                .background(RtColors.Background.copy(alpha = 0.75f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            StatRow(
                listOf(
                    Stat("Speed", Format.speedKmh(sample?.speedMps)),
                    Stat("Lean", Format.lean(sample?.leanDeg), color = leanColor(sample?.leanDeg)),
                    Stat("G", sample?.combinedG?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: Format.DASH, color = RtColors.GForce),
                    Stat("Elev.", Format.altitude(sample?.altitudeM)),
                ),
                style = RtType.metricM,
            )

            Spacer(Modifier.height(20.dp))
            SegmentedTabs(chart, onSelect = { haptics.tick(); vm.selectChart(it) })
            Spacer(Modifier.height(16.dp))

            if (data == null) {
                Shimmer(Modifier.fillMaxWidth().height(110.dp))
            } else if (samples.size < 2) {
                Text("Not enough telemetry was recorded for charts.", style = RtType.caption, color = RtColors.TextSecondary)
            } else {
                val (series, color, negative) = when (chart) {
                    ChartKind.SPEED -> Triple(data.speed, RtColors.Primary, null)
                    ChartKind.LEAN -> Triple(data.lean, RtColors.Right, RtColors.Left)
                    ChartKind.G -> Triple(data.gForce, RtColors.GForce, null)
                    ChartKind.ELEVATION -> Triple(data.elevation, RtColors.Left, null)
                }
                LineChart(
                    title = chart.label, readout = "", series = series, color = color,
                    scrubFraction = scrub, onScrub = vm::scrubTo, negativeColor = negative,
                    unavailableText = if (chart == ChartKind.LEAN) "Lean wasn't recorded for this ride (mount not calibrated or no gyroscope)." else "Not recorded for this ride.",
                    showHeader = false, height = 110.dp,
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = scrub ?: 0f,
                    onValueChange = vm::scrubTo,
                    colors = SliderDefaults.colors(thumbColor = RtColors.TextPrimary, activeTrackColor = RtColors.Primary, inactiveTrackColor = RtColors.Outline),
                    modifier = Modifier.semantics { contentDescription = "Ride timeline" },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(Format.timeOfDay(samples.first().timeMillis), style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                    Text(Format.timeOfDay(samples.last().timeMillis), style = RtType.caption, color = RtColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(20.dp))
            SecondaryButton("Play ride", onReplay, icon = Icons.Rounded.PlayArrow, enabled = (data?.route?.size ?: 0) >= 2)

            RideDynamics(ride)
            Maneuvers(ride)
            TimeBreakdown(ride)

            val events = data?.track?.events.orEmpty().filter { showTurns || !it.type.isTurn }
            SectionHeader("Events") {
                if (data?.track?.events?.any { it.type.isTurn } == true) {
                    TextButton(onClick = { showTurns = !showTurns }) {
                        Text(if (showTurns) "Hide turns" else "Show turns", style = RtType.caption, color = RtColors.Primary)
                    }
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
                        .clickable(role = Role.Button) { vm.scrubToTime(e.timeMillis) }
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
