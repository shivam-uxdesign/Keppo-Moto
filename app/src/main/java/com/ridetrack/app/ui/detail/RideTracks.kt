package com.ridetrack.app.ui.detail

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.ui.common.isTurn
import com.ridetrack.app.ui.common.presentation
import com.ridetrack.app.ui.components.ChartMark
import com.ridetrack.app.ui.components.ChartWindow
import com.ridetrack.app.ui.components.ZoomChart
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.moments.momentTitle
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Ride
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A tappable marker on the dynamics track. */
private data class DynamicsPill(val index: Int, val label: String, val color: Color, val description: String)

/**
 * The timeline under the map: a live readout, then moments, the graph and ride dynamics as
 * tracks on one time axis, with one playhead through all of them.
 */
@Composable
fun RideTracks(
    data: TrackData,
    ride: Ride,
    moments: List<Moment>,
    time: Double,
    fraction: Float,
    lean: Double?,
    window: ChartWindow,
    chart: ChartKind,
    minSpan: Float,
    onCycleChart: () -> Unit,
    onScrub: (Float) -> Unit,
    onWindow: (ChartWindow) -> Unit,
    onResetZoom: () -> Unit,
    onJump: (Int) -> Unit,
    onJumpToTime: (Long) -> Unit,
    onOpenMoment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rt = LocalRtPalette.current
    val sample = data.samples.getOrNull(data.index(fraction))
    val near = moments.minByOrNull { abs(it.timeMillis - time) }?.takeIf { abs(it.timeMillis - time) <= NEAR_MOMENT_MS }
    Column(modifier) {
        // Live readout and the graph picker.
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = RtType.caption.copy(fontFeatureSettings = "tnum")
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(Format.timeOfDay(time.toLong()), style = style, color = RtColors.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(Format.speedWithUnit(sample?.speedMps), style = style, color = RtColors.Primary)
                Spacer(Modifier.width(12.dp))
                Text(Format.lean(lean), style = style, color = leanColor(lean))
                Spacer(Modifier.width(12.dp))
                Text(Format.g(sample?.combinedG), style = style, color = RtColors.GForce)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Show the next graph", onClick = onCycleChart)
                    .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chart.label, style = RtType.caption, color = RtColors.TextPrimary)
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        // Moments, graph and dynamics share the playhead.
        Column(
            Modifier.drawWithContent {
                drawContent()
                if (window.contains(fraction)) {
                    val x = (fraction - window.start) / window.span * size.width
                    drawLine(rt.textPrimary.copy(alpha = 0.85f), Offset(x, 4.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
                    drawCircle(rt.textPrimary, 4.dp.toPx(), Offset(x, 4.dp.toPx()))
                }
            },
        ) {
            if (moments.isNotEmpty()) {
                MomentsTrack(moments, data, window, near?.id, onTap = { m -> if (m.id == near?.id) onOpenMoment(m.id) else onJumpToTime(m.timeMillis) })
            }
            val (series, color, negative) = when (chart) {
                ChartKind.SPEED -> Triple(data.speed, RtColors.Primary, null)
                ChartKind.LEAN -> Triple(data.lean, RtColors.Right, RtColors.Left)
                ChartKind.G -> Triple(data.gForce, RtColors.GForce, null)
                ChartKind.ELEVATION -> Triple(data.elevation, RtColors.Left, null)
            }
            val marks = data.track.events.filterNot { it.type.isTurn }.map { e -> ChartMark(data.fractionAt(e.timeMillis), e.presentation().color) }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.Surface)) {
                ZoomChart(
                    series = series, color = color, negativeColor = negative,
                    scrub = fraction, window = window, onScrub = onScrub, onWindow = onWindow,
                    minSpan = minSpan, marks = marks, height = 96.dp, showScrubLine = false,
                    unavailableText = if (chart == ChartKind.LEAN) "Lean wasn't recorded for this ride." else "Not recorded for this ride.",
                )
            }
            DynamicsTrack(data, ride, window, onJump)
        }

        // Time axis and where the zoom window sits in the whole ride.
        val winStart = data.timeAt(window.start).toLong()
        val winEnd = data.timeAt(window.end).toLong()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.timeOfDay(winStart), style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextTertiary)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (window.isZoomed) {
                    Text(
                        "${Format.duration(winEnd - winStart)} shown · Reset",
                        style = RtType.caption.copy(fontSize = 11.sp),
                        color = RtColors.Primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClickLabel = "Show the whole ride", onClick = onResetZoom)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                } else {
                    Text("Pinch or double-tap to zoom", style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextTertiary)
                }
            }
            Text(Format.timeOfDay(winEnd), style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextTertiary)
        }
        // Zoomed in, the bar scrolls the view: drag the highlight along the ride, or tap to jump.
        val current = rememberUpdatedState(window)
        val zoomed = window.isZoomed
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .height(if (zoomed) 24.dp else 7.dp)
                .then(
                    if (!zoomed) Modifier else Modifier
                        .semantics { contentDescription = "Zoomed part of the ride. Drag to scroll." }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, dx ->
                                change.consume()
                                onWindow(current.value.pan(dx / size.width))
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val w = current.value
                                onWindow(ChartWindow.of(pos.x / size.width - w.span / 2, w.span))
                            }
                        },
                ),
        ) {
            val barH = if (zoomed) 6.dp.toPx() else 3.dp.toPx()
            val top = (size.height - barH) / 2
            val r = CornerRadius(barH / 2)
            drawRoundRect(rt.hairline, topLeft = Offset(0f, top), size = Size(size.width, barH), cornerRadius = r)
            drawRoundRect(
                rt.primary,
                topLeft = Offset(window.start * size.width, top),
                size = Size((window.span * size.width).coerceAtLeast(barH), barH),
                cornerRadius = r,
            )
        }
    }
}

/** Thumbnails at their times, nudged apart where they'd overlap, with a tick at the exact time. */
@Composable
private fun MomentsTrack(moments: List<Moment>, data: TrackData, window: ChartWindow, selectedId: String?, onTap: (Moment) -> Unit) {
    val visible = moments.map { it to data.fractionAt(it.timeMillis) }.filter { window.contains(it.second) }.sortedBy { it.second }
    val tickColors = visible.map { momentColor(it.first) }
    Box(Modifier.fillMaxWidth().height(46.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            visible.forEachIndexed { i, (_, f) ->
                val x = (f - window.start) / window.span * size.width
                drawRect(tickColors[i], Offset(x - 1.dp.toPx(), size.height - 6.dp.toPx()), Size(2.dp.toPx(), 5.dp.toPx()))
            }
        }
        SpreadRow(
            centers = visible.map { (_, f) -> (f - window.start) / window.span },
            gap = 3.dp.toPxInt(),
            modifier = Modifier.fillMaxWidth().height(38.dp),
        ) {
            visible.forEach { (m, _) ->
                val selected = m.id == selectedId
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, label = "thumbScale")
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(32.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(shape)
                        .background(RtColors.Surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) RtColors.Primary else RtColors.Hairline, shape)
                        .clickable(role = Role.Button, onClickLabel = if (selected) "Open moment" else "Show on timeline") { onTap(m) }
                        .semantics { contentDescription = "${momentTitle(m)}, ${Format.timeOfDay(m.timeMillis)}" },
                ) {
                    Thumb(m.thumb, Modifier.fillMaxSize(), maxEdge = 128)
                    Box(Modifier.padding(3.dp).size(7.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(1.dp).background(momentColor(m), RoundedCornerShape(50)))
                }
            }
        }
    }
}

/** Pills where the ride's extremes happened: tap one to jump there. */
@Composable
private fun DynamicsTrack(data: TrackData, ride: Ride, window: ChartWindow, onJump: (Int) -> Unit) {
    val rt = LocalRtPalette.current
    val s = ride.stats
    val pills = remember(data, ride) {
        listOfNotNull(
            data.maxLeftIndex?.let { DynamicsPill(it, "${s.maxLeftLeanDeg?.roundToInt() ?: 0}°L", rt.left, "Most lean left") },
            data.maxRightIndex?.let { DynamicsPill(it, "${s.maxRightLeanDeg?.roundToInt() ?: 0}°R", rt.right, "Most lean right") },
            data.hardestBrakeIndex?.let { DynamicsPill(it, String.format(Locale.US, "%.2fG", abs(s.maxBrakeG ?: 0.0)), rt.brake, "Hardest braking") },
            data.strongestAccelIndex?.let { DynamicsPill(it, String.format(Locale.US, "%.2fG", abs(s.maxAccelG ?: 0.0)), rt.accel, "Strongest acceleration") },
            data.initialIndex.takeIf { data.samples.size >= 2 }?.let { DynamicsPill(it, "${Format.speedKmh(s.maxSpeedMps)} km/h", rt.gForce, "Top speed") },
        )
    }
    val visible = pills.map { it to data.fraction(it.index) }.filter { window.contains(it.second) }.sortedBy { it.second }
    SpreadRow(
        centers = visible.map { (_, f) -> (f - window.start) / window.span },
        gap = 3.dp.toPxInt(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(24.dp),
    ) {
        visible.forEach { (p, _) ->
            Text(
                p.label,
                style = RtType.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                color = p.color,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.color.copy(alpha = 0.12f))
                    .border(1.dp, p.color.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Show on timeline") { onJump(p.index) }
                    .semantics { contentDescription = "${p.description}, ${p.label}" }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun androidx.compose.ui.unit.Dp.toPxInt(): Int = with(androidx.compose.ui.platform.LocalDensity.current) { this@toPxInt.roundToPx() }

/**
 * Places each child centred on its [centers] fraction of the width, pushed apart just enough
 * that neighbours don't overlap and kept inside the row.
 */
@Composable
private fun SpreadRow(centers: List<Float>, gap: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val lefts = spreadApart(
            FloatArray(placeables.size) { i -> centers.getOrElse(i) { 0f } * width },
            IntArray(placeables.size) { placeables[it].width },
            gap,
            width,
        )
        layout(width, constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: (placeables.maxOfOrNull { it.height } ?: 0)) {
            placeables.forEachIndexed { i, p -> p.place(lefts[i], 0) }
        }
    }
}

/**
 * Left edges for items of [widths] wanting to be centred at [centers] (ascending): each is
 * pushed right past its neighbour plus [gap], then the row is pulled back inside [width].
 */
internal fun spreadApart(centers: FloatArray, widths: IntArray, gap: Int, width: Int): IntArray {
    val n = centers.size
    val lefts = IntArray(n)
    for (i in 0 until n) {
        val want = (centers[i] - widths[i] / 2f).roundToInt().coerceAtLeast(0)
        lefts[i] = if (i == 0) want else maxOf(want, lefts[i - 1] + widths[i - 1] + gap)
    }
    for (i in n - 1 downTo 0) {
        val limit = if (i == n - 1) width - widths[i] else lefts[i + 1] - gap - widths[i]
        if (lefts[i] > limit) lefts[i] = limit
    }
    for (i in 0 until n) lefts[i] = lefts[i].coerceAtLeast(0)
    return lefts
}

/** How close (in time) a moment must be to the playhead to be highlighted. */
private const val NEAR_MOMENT_MS = 90_000L
