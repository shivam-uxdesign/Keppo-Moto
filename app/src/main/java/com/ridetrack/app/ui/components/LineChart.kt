package com.ridetrack.app.ui.components

import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/**
 * A time series; NaN marks missing data and is drawn as a gap (never as zero).
 */
class ChartSeries(val values: FloatArray, val symmetric: Boolean = false, val floorZero: Boolean = false) {
    val hasData: Boolean = values.any { !it.isNaN() }
    val min: Float
    val max: Float

    init {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in values) if (!v.isNaN()) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (!hasData) {
            lo = 0f
            hi = 1f
        }
        if (symmetric) {
            val m = maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi), 1e-3f)
            lo = -m
            hi = m
        } else if (floorZero) {
            lo = minOf(0f, lo)
        }
        if (hi - lo < 1e-3f) hi = lo + 1f
        min = lo
        max = hi
    }
}

/**
 * Interactive line chart with a shared scrub cursor. [scrubFraction] in 0..1.
 */
@Composable
fun LineChart(
    title: String,
    readout: String,
    series: ChartSeries,
    color: Color,
    scrubFraction: Float?,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    negativeColor: Color? = null,
    unavailableText: String = "Unavailable for this ride",
    showHeader: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val rt = LocalRtPalette.current
    Column(modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(title, Modifier.weight(1f))
                Text(readout, style = RtType.metricS, color = RtColors.TextPrimary)
            }
            Spacer(Modifier.height(RtDimens.xs))
        }
        if (!series.hasData) {
            Text(unavailableText, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.height(height))
            return@Column
        }
        val path = remember(series) { Path() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "$title chart. Drag to scrub the ride timeline." }
                .pointerInput(Unit) {
                    detectTapGestures { onScrub((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { onScrub((it.x / size.width).coerceIn(0f, 1f)) },
                    ) { change, _ ->
                        change.consume()
                        onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                },
        ) {
            val v = series.values
            if (v.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            fun y(value: Float) = h - (value - series.min) / (series.max - series.min) * h
            val zeroY = y(0f).coerceIn(0f, h)
            if (series.min < 0f && series.max > 0f) {
                drawLine(rt.outline, Offset(0f, zeroY), Offset(w, zeroY), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            path.reset()
            var penDown = false
            val step = w / (v.size - 1)
            for (i in v.indices) {
                val value = v[i]
                if (value.isNaN()) {
                    penDown = false
                    continue
                }
                val px = i * step
                val py = y(value)
                if (penDown) path.lineTo(px, py) else path.moveTo(px, py)
                penDown = true
            }
            val brush = if (negativeColor != null && series.min < 0f && series.max > 0f) {
                val split = (zeroY / h).coerceIn(0f, 1f)
                Brush.verticalGradient(0f to color, split to color, split to negativeColor, 1f to negativeColor)
            } else {
                Brush.linearGradient(listOf(color, color))
            }
            drawPath(path, brush, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            if (scrubFraction != null) {
                val x = scrubFraction * w
                drawLine(rt.textPrimary.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), 1.5.dp.toPx())
                val idx = (scrubFraction * (v.size - 1)).toInt().coerceIn(0, v.size - 1)
                val value = v[idx]
                if (!value.isNaN()) drawCircle(rt.textPrimary, 4.dp.toPx(), Offset(x, y(value)))
            }
        }
    }
}
