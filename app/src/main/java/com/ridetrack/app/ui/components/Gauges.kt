package com.ridetrack.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.cos
import kotlin.math.sin

fun leanColor(deg: Double?) = when (Format.leanSide(deg)) {
    Format.LeanSide.LEFT -> RtColors.Left
    Format.LeanSide.RIGHT -> RtColors.Right
    else -> RtColors.TextPrimary
}

/**
 * Thin upper-arc lean gauge (±60°): a hairline track, a coloured arc from upright toward
 * the lean side and a knob at the current angle. Max-lean marks are faint ticks.
 */
@Composable
fun LeanArc(leanDeg: Double?, modifier: Modifier = Modifier, maxLeft: Double? = null, maxRight: Double? = null) {
    val animated by animateFloatAsState(
        targetValue = (leanDeg ?: 0.0).toFloat().coerceIn(-60f, 60f),
        animationSpec = tween(150),
        label = "lean",
    )
    val color = leanColor(leanDeg)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(2.7f)
            .semantics { contentDescription = "Lean ${Format.lean(leanDeg)}" },
    ) {
        val stroke = 3.dp.toPx()
        val knob = 6.dp.toPx()
        val radius = minOf(size.width / 2f, size.height) - knob
        val center = Offset(size.width / 2f, radius + knob)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(RtColors.Outline, startAngle = 210f, sweepAngle = 120f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        fun point(deg: Double, r: Float): Offset {
            val a = Math.toRadians(270.0 + deg)
            return Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
        }
        fun mark(deg: Double, c: androidx.compose.ui.graphics.Color) {
            drawLine(c, point(deg, radius - 9.dp.toPx()), point(deg, radius + 3.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        mark(0.0, RtColors.TextTertiary)
        maxLeft?.let { mark(-it.coerceAtMost(60.0), RtColors.Left.copy(alpha = 0.6f)) }
        maxRight?.let { mark(it.coerceAtMost(60.0), RtColors.Right.copy(alpha = 0.6f)) }
        if (leanDeg != null) {
            drawArc(color, startAngle = 270f, sweepAngle = animated, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawCircle(color, radius = knob, center = point(animated.toDouble(), radius))
        }
    }
}

/** Friction-circle G indicator: braking up, acceleration down, cornering left/right. */
@Composable
fun GForceIndicator(longitudinalG: Double?, lateralG: Double?, modifier: Modifier = Modifier) {
    val x by animateFloatAsState((lateralG ?: 0.0).toFloat().coerceIn(-1f, 1f), tween(150), label = "gx")
    val y by animateFloatAsState((longitudinalG ?: 0.0).toFloat().coerceIn(-1f, 1f), tween(150), label = "gy")
    val available = longitudinalG != null || lateralG != null
    Box(
        modifier.semantics {
            contentDescription = "G-force: longitudinal ${Format.gSigned(longitudinalG)}, lateral ${Format.gSigned(lateralG)}"
        },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(16.dp),
        ) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(RtColors.Outline, radius = r, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(RtColors.Outline, radius = r / 2f, center = c, style = Stroke(1.dp.toPx()))
            if (available) {
                val dot = Offset(c.x + x * r, c.y + y * r)
                drawCircle(RtColors.GForce.copy(alpha = 0.2f), radius = 12.dp.toPx(), center = dot)
                drawCircle(RtColors.GForce, radius = 5.dp.toPx(), center = dot)
            }
        }
        Text("BRAKE", style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.TopCenter))
        Text("ACCEL", style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Top of the tach scale: a little headroom above the redline, else a generic 12k. */
fun tachMaxRpm(redlineRpm: Int?): Double =
    redlineRpm?.let { kotlin.math.ceil(it * 1.12 / 1000.0) * 1000.0 } ?: 12_000.0

/** Rev colour: calm → warm near the redline → red past it (unknown redline stays calm). */
fun rpmColor(rpm: Double?, redlineRpm: Int?): androidx.compose.ui.graphics.Color {
    val red = redlineRpm ?: return RtColors.Primary
    return when {
        rpm == null -> RtColors.TextTertiary
        rpm >= red -> RtColors.Error
        rpm >= red * 0.85 -> RtColors.Warning
        else -> RtColors.Primary
    }
}

/**
 * Tachometer arc wrapped around [content] (the speed readout). 240° sweep, a faint tick
 * every 1,000 rpm, the redline band drawn on the track when the redline is known.
 * Only shown when an engine link actually reports RPM.
 */
@Composable
fun RevMeter(
    rpm: Double?,
    redlineRpm: Int?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val max = tachMaxRpm(redlineRpm)
    val fraction by animateFloatAsState(
        targetValue = ((rpm ?: 0.0) / max).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "rpm",
    )
    val color = rpmColor(rpm, redlineRpm)
    Box(
        modifier
            .aspectRatio(1.18f)
            .semantics { contentDescription = "Engine ${Format.rpm(rpm)} rpm" }
            .drawBehind {
                val stroke = 5.dp.toPx()
                val radius = minOf(size.width, size.height * 1.18f) / 2f - stroke
                val center = Offset(size.width / 2f, radius + stroke)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                val start = 150f
                val sweep = 240f
                drawArc(RtColors.Outline, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                redlineRpm?.let { red ->
                    val from = (red / max).toFloat().coerceIn(0f, 1f)
                    drawArc(RtColors.Error.copy(alpha = 0.35f), start + sweep * from, sweep * (1f - from), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                val thousands = (max / 1000).toInt()
                for (k in 1 until thousands) {
                    val a = Math.toRadians((start + sweep * (k * 1000 / max)).toDouble())
                    val outer = radius - stroke * 1.4f
                    val inner = outer - (if (k % 5 == 0) 9.dp.toPx() else 5.dp.toPx())
                    drawLine(
                        RtColors.TextTertiary,
                        Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                        Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (rpm != null && fraction > 0f) {
                    drawArc(color, start, sweep * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Compact horizontal rev bar (pop-up HUD): segmented, coloured by zone. */
@Composable
fun RevBar(rpm: Double?, redlineRpm: Int?, modifier: Modifier = Modifier, segments: Int = 24) {
    val max = tachMaxRpm(redlineRpm)
    Canvas(modifier.fillMaxWidth().aspectRatio(18f)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val lit = (((rpm ?: 0.0) / max) * segments).toInt()
        for (i in 0 until segments) {
            val segRpm = (i + 1) * max / segments
            val on = rpm != null && i < lit
            val c = if (on) rpmColor(segRpm, redlineRpm) else if (redlineRpm != null && segRpm > redlineRpm) RtColors.Error.copy(alpha = 0.25f) else RtColors.Outline
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
        }
    }
}
