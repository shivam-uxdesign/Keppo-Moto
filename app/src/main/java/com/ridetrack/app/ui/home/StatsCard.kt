package com.ridetrack.app.ui.home

import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberReduceMotion
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** The speed arc on the bests tile is full at this speed. */
private const val ARC_FULL_KMH = 140.0

/** Today / Week / Month at a glance: distance, a chart that fits the period, and bests. */
@Composable
fun StatsCard(stats: HomeStats, trace: SpeedTrace?, modifier: Modifier = Modifier) {
    var period by rememberSaveable { mutableStateOf(StatPeriod.TODAY) }
    val p = stats[period]
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        PeriodSwitch(period) { period = it }

        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
            val km by animateFloatAsState((p.distanceM / 1000.0).toFloat(), tween(550), label = "km")
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Format.distanceValue(km * 1000.0),
                    style = RtType.metricXL.copy(fontSize = 48.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraLight),
                    color = RtColors.TextPrimary,
                    modifier = Modifier.semantics { contentDescription = "${Format.distance(p.distanceM)} ${period.label.lowercase()}" },
                )
                Text("km", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                SmallStat(p.rides.toString(), if (p.rides == 1) "ride" else "rides")
                SmallStat(if (p.movingMillis > 0) Format.duration(p.movingMillis) else Format.DASH, "moving")
            }
        }
        p.comparison?.let {
            Text(it, style = RtType.caption.copy(fontSize = 12.sp), color = RtColors.Primary, modifier = Modifier.padding(top = 6.dp))
        }

        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(80.dp)) {
            when (period) {
                StatPeriod.TODAY -> TraceChart(trace)
                StatPeriod.WEEK -> DayBars(p, labels = listOf("M", "T", "W", "T", "F", "S", "S"), barWidthFraction = 0.62f)
                StatPeriod.MONTH -> {
                    val month = LocalDate.now().month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
                    DayBars(p, labels = null, barWidthFraction = 0.7f, footer = listOf("$month 1", "$month 15", "Today"))
                }
            }
        }

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BestTile("Top speed", Modifier.weight(1f), value = {
                Text(
                    Format.speedKmh(p.topSpeedMps),
                    style = RtType.metricS.copy(fontWeight = FontWeight.Light),
                    color = RtColors.TextPrimary,
                )
                if (p.topSpeedMps != null) Text(" km/h", style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp))
            }) { SpeedArc(p.topSpeedMps) }
            BestTile("Max lean", Modifier.weight(1f), value = {
                val side = Format.leanSide(p.maxLeanDeg)
                Text(
                    Format.lean(p.maxLeanDeg),
                    style = RtType.metricS.copy(fontWeight = FontWeight.Light),
                    color = if (side == Format.LeanSide.RIGHT) RtColors.Right else RtColors.Left,
                )
            }) { LeanHorizon(p.maxLeanDeg) }
        }
    }
}

@Composable
private fun SmallStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = RtType.metricS.copy(fontSize = 19.sp, fontWeight = FontWeight.Light), color = RtColors.TextPrimary)
        Text(label, style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextSecondary)
    }
}

/** Segmented Today / Week / Month; the white pill slides to the pick. */
@Composable
private fun PeriodSwitch(period: StatPeriod, onPick: (StatPeriod) -> Unit) {
    val itemWidth = 72.dp
    val pillX by animateDpAsState(itemWidth * period.ordinal, spring(dampingRatio = 0.75f, stiffness = 500f), label = "pill")
    Box(
        Modifier
            .clip(CircleShape)
            .background(RtColors.Background)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = pillX)
                .size(itemWidth, 30.dp)
                .clip(CircleShape)
                .background(RtColors.TextPrimary),
        )
        Row {
            StatPeriod.entries.forEach { option ->
                val on = option == period
                Box(
                    Modifier
                        .size(itemWidth, 30.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onPick(option) }
                        .semantics { selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.label, style = RtType.caption, color = if (on) RtColors.OnInverse else RtColors.TextSecondary)
                }
            }
        }
    }
}

/** 0→1 each time the chart appears, for bars growing and the line drawing in. */
@Composable
private fun rememberEntrance(key: Any?): Float {
    val reduce = rememberReduceMotion()
    val progress = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(700, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))) }
    return progress.value
}

/** The latest ride's speed over time, with its peak marked. */
@Composable
private fun TraceChart(trace: SpeedTrace?) {
    val rt = LocalRtPalette.current
    if (trace == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Your ride's speed trace appears here", style = RtType.caption, color = RtColors.TextTertiary)
        }
        return
    }
    val progress = rememberEntrance(trace)
    val measurer = rememberTextMeasurer()
    val peakLabel = "${Format.speedKmh(trace.speedsMps.getOrNull(trace.peakIndex))} km/h"
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .semantics { contentDescription = "Speed during the ride, peaking at $peakLabel" },
        ) {
            val top = trace.speedsMps.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val labelRoom = 14.dp.toPx()
            val h = size.height
            fun pt(i: Int) = Offset(
                size.width * i / (trace.speedsMps.size - 1),
                h - 1f - ((trace.speedsMps[i] / top).toFloat() * (h - labelRoom - 2f)),
            )
            val line = Path().apply {
                moveTo(pt(0).x, pt(0).y)
                for (i in 1 until trace.speedsMps.size) lineTo(pt(i).x, pt(i).y)
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, h)
                lineTo(0f, h)
                close()
            }
            drawLine(rt.textPrimary.copy(alpha = 0.08f), Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f))
            clipRect(right = size.width * progress) {
                drawPath(area, Brush.verticalGradient(listOf(rt.primary.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, rt.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (progress > 0.95f && trace.peakIndex >= 0) {
                val peak = pt(trace.peakIndex)
                drawCircle(rt.surface, 6.dp.toPx(), peak)
                drawCircle(rt.gForce, 4.dp.toPx(), peak)
                val text = measurer.measure(peakLabel, TextStyle(color = rt.gForce, fontSize = 11.sp))
                val x = (peak.x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, (peak.y - text.size.height - 6.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(Format.timeOfDay(trace.startMillis), style = FooterStyle, color = RtColors.TextTertiary)
            Text(
                if (trace.isToday) "Speed · today's ride" else "Speed · last ride, ${Format.rideDate(trace.startMillis)}",
                style = FooterStyle,
                color = RtColors.TextTertiary,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(Format.timeOfDay(trace.endMillis), style = FooterStyle, color = RtColors.TextTertiary)
        }
    }
}

private val FooterStyle = TextStyle(fontSize = 11.sp)

/** Distance per day; today in teal, days with rides brighter than empty ones. */
@Composable
private fun DayBars(p: PeriodStats, labels: List<String>?, barWidthFraction: Float, footer: List<String>? = null) {
    val rt = LocalRtPalette.current
    val progress = rememberEntrance(p.days.size)
    val max = p.days.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .semantics { contentDescription = "Distance per day" },
        ) {
            val n = p.days.size
            val slot = size.width / n
            val w = (slot * barWidthFraction).coerceAtMost(30.dp.toPx())
            val radius = if (n > 10) 2.dp.toPx() else 8.dp.toPx()
            p.days.forEachIndexed { i, m ->
                val stagger = ((progress * 1.4f) - i * (0.4f / n)).coerceIn(0f, 1f)
                val full = if (m > 0) (m / max).toFloat() * size.height else 3.dp.toPx()
                val h = full * stagger
                val color = when {
                    i == p.todayIndex -> rt.primary
                    m > 0 -> rt.textPrimary.copy(alpha = 0.4f)
                    i > p.todayIndex -> rt.textPrimary.copy(alpha = 0.04f)
                    else -> rt.textPrimary.copy(alpha = 0.09f)
                }
                drawRoundRect(
                    color,
                    topLeft = Offset(slot * i + (slot - w) / 2, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(minOf(radius, w / 2)),
                )
            }
        }
        if (labels != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                labels.forEachIndexed { i, l ->
                    val today = i == p.todayIndex
                    Text(
                        l,
                        style = FooterStyle.copy(fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (today) RtColors.Primary else RtColors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (footer != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                footer.forEachIndexed { i, t ->
                    Text(t, style = FooterStyle, color = if (i == footer.lastIndex) RtColors.Primary else RtColors.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun BestTile(label: String, modifier: Modifier, value: @Composable () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(RtColors.Background)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = FooterStyle, color = RtColors.TextSecondary)
            Row(verticalAlignment = Alignment.Bottom) { value() }
        }
    }
}

@Composable
private fun SpeedArc(topMps: Double?) {
    val rt = LocalRtPalette.current
    val target = ((topMps ?: 0.0) * 3.6 / ARC_FULL_KMH).coerceIn(0.0, 1.0).toFloat()
    val fill by animateFloatAsState(target, tween(500), label = "arc")
    Canvas(Modifier.size(36.dp)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        drawArc(rt.surfaceRaised, 135f, 270f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fill > 0f) drawArc(rt.gForce, 135f, 270f * fill, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A horizon tilted by the lean, like the attitude indicator on the live screen. */
@Composable
private fun LeanHorizon(leanDeg: Double?) {
    val angle by animateFloatAsState((leanDeg ?: 0.0).toFloat(), tween(500), label = "lean")
    val color = if ((leanDeg ?: 0.0) > 0) RtColors.Right else RtColors.Left
    val horizon = RtColors.TextPrimary.copy(alpha = 0.14f)
    Canvas(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(RtColors.SurfaceRaised),
    ) {
        val c = center
        drawLine(horizon, Offset(4.dp.toPx(), c.y), Offset(size.width - 4.dp.toPx(), c.y), 1.dp.toPx())
        if (leanDeg != null) {
            rotate(angle, c) {
                drawLine(color, Offset(-6.dp.toPx(), c.y), Offset(size.width + 6.dp.toPx(), c.y), 2.dp.toPx())
            }
        }
    }
}
